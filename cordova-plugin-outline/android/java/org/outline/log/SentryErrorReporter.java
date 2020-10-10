// Copyright 2018 The Outline Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.outline.log;

import android.content.Context;
import android.util.Log;
import io.sentry.android.core.SentryAndroid;
import io.sentry.core.Breadcrumb;
import io.sentry.core.Sentry;
import io.sentry.core.SentryEvent;
import io.sentry.core.SentryLevel;
import io.sentry.core.protocol.Contexts;
import io.sentry.core.protocol.Device;
import io.sentry.core.protocol.Message;
import io.sentry.core.protocol.OperatingSystem;
import java.lang.IllegalStateException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Wrapper class for the Sentry error reporting framework.
 */
public class SentryErrorReporter {
  private static final int MAX_BREADCRUMBS = 100;
  private static final String CATEGORY_VPN_PROCESS = "vpn";

  // Disallow instantiation in favor of a purely static class.
  private SentryErrorReporter() {}

  // Queue of messages waiting to be sent once Sentry is initialized.
  private static Queue<Breadcrumb> breadcrumbsQueue = new LinkedList<>();

  /**
   * Handler that records logs with level INFO and above to Sentry.
   * Exceptions are only logged locally to avoid sending sensitive data to the error reporting
   * framework as part of stack traces.
   */
  public final static Handler BREADCRUMB_LOG_HANDLER = new Handler() {
    @Override
    public void publish(LogRecord record) {
      Level level = record.getLevel();
      String tag = OutlineLogger.loggerNameToTag(record.getLoggerName());
      String msg = record.getMessage();
      try {
        final String breadcrumb = String.format(Locale.ROOT, "%s:%s", tag, msg);
        int levelValue = level.intValue();
        if (levelValue >= Level.SEVERE.intValue()) {
          recordBreadcrumb(breadcrumb, SentryLevel.ERROR);
        } else if (levelValue >= Level.WARNING.intValue()) {
          recordBreadcrumb(breadcrumb, SentryLevel.WARNING);
        } else if (levelValue >= Level.INFO.intValue()) {
          recordBreadcrumb(breadcrumb, SentryLevel.INFO);
        }
      } catch (RuntimeException e) {
        Log.e("SentryLogHandler",
            String.format(Locale.ROOT, "Error logging message: [%tag] %s", tag, msg), e);
      }
    }

    // Must implement abstract methods even though we don't need them.
    @Override
    public void close() throws SecurityException {}

    @Override
    public void flush() {}
  };

  /**
   * Initializes the error reporting framework with the given credentials.
   * Configures an Android uncaught exception handler which sends events to Sentry.
   *
   * @param context Android application Context
   * @param dsn Sentry API Key
   */
  public static void init(Context context, final String dsn) {
    SentryAndroid.init(context, options -> {
      options.setDsn(dsn);
      options.setMaxBreadcrumbs(MAX_BREADCRUMBS);
      options.setBeforeSend(((event, hint) -> {
        try {
          return removeSentryEventPii(event);
        } catch (Exception e) {
          Log.e(SentryErrorReporter.class.getName(), "Failed to remove PII from Sentry event.", e);
        }
        // Don't send the event if we weren't able to remove PII.
        return null;
      }));
    });

    // Record all queued breadcrumbs.
    while (breadcrumbsQueue.size() > 0) {
      Sentry.addBreadcrumb(breadcrumbsQueue.remove());
    }
  }

  /**
   * Sends previously recorded errors and messages to Sentry. Associate the report
   * with the provided event id.
   *
   * @param eventId, unique identifier i.e. the event id for a error report in raven-js.
   */
  public static void send(final String eventId) throws IllegalStateException {
    recordVpnProcessLogs();
    final String uuid = eventId != null ? eventId : UUID.randomUUID().toString();
    // Associate this report with the event ID generated by Raven JS for cross-referencing. If the
    // ID is not present, use a random UUID to disambiguate the report message so it doesn't get
    // clustered with other reports. Clustering retains the report data on the server side, whereas
    // inactivity results in its deletion after 90 days.
    final SentryEvent event = new SentryEvent();
    final Message message = new Message();
    message.setMessage(String.format(Locale.ROOT, "Android report (%s)", uuid));
    event.setMessage(message);
    event.setTag("user_event_id", uuid);
    Sentry.captureEvent(event);
  }

  private static void recordVpnProcessLogs() {
    final List<String> vpnProcessLogs = OutlineLogger.getVpnProcessLogs();
    int numBreadcrumbsRecorded = 0;
    // Logs are sorted increasingly by timestamp.
    for (int i = vpnProcessLogs.size() - 1; i > 0; --i) {
      if (numBreadcrumbsRecorded >= MAX_BREADCRUMBS / 2) {
        // Send at most MAX_BREADCRUMBS/2 breadcrumbs from VPN process logs.
        break;
      }
      recordVpnProcessBreadcrumb(vpnProcessLogs.get(i));
      numBreadcrumbsRecorded++;
    }
  }

  // Record a log message as a breadcrumb to send with the next error report.
  private static void recordBreadcrumb(final String msg, SentryLevel level) {
    final Breadcrumb breadcrumb = new Breadcrumb(msg);
    breadcrumb.setLevel(level);
    addBreadcrumb(breadcrumb);
  }

  private static void recordVpnProcessBreadcrumb(final String msg) {
    final Breadcrumb breadcrumb = new Breadcrumb(msg);
    breadcrumb.setCategory(CATEGORY_VPN_PROCESS);
    addBreadcrumb(breadcrumb);
  }

  private static void addBreadcrumb(Breadcrumb breadcrumb) {
    if (Sentry.isEnabled()) {
      Sentry.addBreadcrumb(breadcrumb);
    } else {
      breadcrumbsQueue.add(breadcrumb);
    }
  }

  // Removes personally identifiably information and unnecessary metadata from a Sentry event.
  // Ensures that the Android device ID is not sent.
  private static SentryEvent removeSentryEventPii(final SentryEvent event) {
    final Contexts contexts = event.getContexts();
    final Device device = contexts.getDevice();
    device.setBootTime(null);
    device.setCharging(null);
    device.setExternalFreeStorage(null);
    device.setExternalStorageSize(null);
    device.setId(null);
    device.setOrientation(null);
    device.setScreenDensity(null);
    device.setScreenDpi(null);
    device.setScreenHeightPixels(null);
    device.setScreenResolution(null);
    device.setScreenWidthPixels(null);

    final OperatingSystem os = contexts.getOperatingSystem();
    os.setRooted(null);

    contexts.setDevice(device);
    contexts.setOperatingSystem(os);
    event.setContexts(contexts);
    event.setUser(null);
    event.removeTag("os.rooted");
    event.removeTag("user");
    return event;
  }
}
