package com.stickerbridge.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** User-facing recovery for the existing accessibility service. */
public final class PickerSettings {
    private static ComponentName component(Context c){return new ComponentName(c,BridgeAccessibility.class);}
    public static boolean enabled(Context c){
        try{
            String services=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(services!=null)for(String value:services.split(":"))if(component(c).equals(ComponentName.unflattenFromString(value)))return true;
        }catch(Exception ignored){}return false;
    }
    public static void open(Activity activity){
        try{activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(":settings:fragment_args_key",component(activity).flattenToString()));Jobs.toast(activity,"在系统无障碍设置中，找到并开启“表情直达 · 框选保存”");}
        catch(Exception error){Jobs.toast(activity,"请打开系统的无障碍设置，找到“表情直达 · 框选保存”并开启");}
    }
    public static String message(Context c){
        BridgeAccessibility service=BridgeAccessibility.live;
        if(service==null)return enabled(c)?"选取暂不可用。请在系统无障碍设置中，将“表情直达 · 框选保存”关闭后重新开启。":"请在系统无障碍设置中，找到并开启“表情直达 · 框选保存”。";
        if(!State.prefs(c).getBoolean("enabled",true))return "悬浮按钮已隐藏，可在“更多”中恢复。";
        org.json.JSONObject picker=service.selectionStatus();
        if(c.getPackageName().equals(picker.optString("primaryPackage")))return "选取已开启，打开抖音后显示绿色“选”。";
        return picker.optBoolean("entryVisible")?"绿色“选”按钮已显示":"选取已开启，可在“更多”中恢复悬浮按钮。";
    }
}
