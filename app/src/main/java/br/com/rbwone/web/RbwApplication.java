package br.com.rbwone.web;

import android.app.Application;

public final class RbwApplication extends Application {
    private NotificationCoordinator notifications;
    private PhoneCallAlerts phoneCalls;
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannels.ensure(this);
        notifications = new NotificationCoordinator(this);
        phoneCalls = new PhoneCallAlerts(this);
    }
    public NotificationCoordinator notifications() { return notifications; }
    public PhoneCallAlerts phoneCalls() { return phoneCalls; }
    public PhoneCallAlerts createPhoneCallsForMain() {
        if (phoneCalls != null) phoneCalls.close();
        phoneCalls = new PhoneCallAlerts(this);
        return phoneCalls;
    }
}
