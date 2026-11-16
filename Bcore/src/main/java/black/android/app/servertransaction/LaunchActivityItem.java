package black.android.app.servertransaction;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BField;

@BClassName("android.app.servertransaction.LaunchActivityItem")
public interface LaunchActivityItem {
    @BField
    IBinder mActivityToken();

    @BField
    ActivityInfo mInfo();

    @BField
    Intent mIntent();
}
