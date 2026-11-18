package com.stickerbridge.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Owner-only setup and node diagnostics. No injected input or QQ automation. */
public final class ControlProvider extends ContentProvider {
    private void guard(){int uid=Binder.getCallingUid();if(uid!=2000&&uid!=0&&uid!=Process.myUid())throw new SecurityException("Only the phone owner via ADB can use setup commands");}
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        guard();Bundle out=new Bundle();
        if(!"pair".equals(method)&&!Diagnostics.debug(getContext()))throw new SecurityException("Use an explicit development diagnostics build");
        try{
            if("pair".equals(method))out.putString("token",State.token(getContext()));
            else if("status".equals(method))out.putString("json",State.status(getContext()).toString());
            else if("ui".equals(method)){org.json.JSONArray list=new org.json.JSONArray();if(BridgeAccessibility.live!=null)dump(BridgeAccessibility.live.sourceRoot(),list);out.putString("json",list.toString());}
            else if("targets".equals(method)){org.json.JSONArray list=new org.json.JSONArray();if(BridgeAccessibility.live!=null){android.util.DisplayMetrics metrics=getContext().getResources().getDisplayMetrics();for(SelectionTargets.Target target:SelectionTargets.collect(BridgeAccessibility.live.sourceRoot(),metrics.density,BridgeAccessibility.live.screenBounds().width(),BridgeAccessibility.live.screenBounds().height()))list.put(new org.json.JSONObject().put("id",target.id).put("description",target.description).put("bounds",target.bounds.flattenToString()).put("nodeBounds",target.nodeBounds.flattenToString()).put("rank",target.rank).put("previewViewport",target.previewViewport).put("animationExpected",target.animationExpected));}out.putString("json",list.toString());}
            else if("enable".equals(method)||"disable".equals(method)){State.prefs(getContext()).edit().putBoolean("enabled","enable".equals(method)).commit();State.MAIN.post(()->{if(BridgeAccessibility.live!=null)BridgeAccessibility.live.refreshBubble();State.changed();});out.putBoolean("ok",true);}
            else if("gallery".equals(method))out.putBoolean("accepted",Jobs.openLibrary(getContext(),"more".equals(arg)));
            else if("items".equals(method)){
                org.json.JSONArray list=new org.json.JSONArray();for(int i=0;i<State.gallery.length();i++){org.json.JSONObject item=new org.json.JSONObject(State.gallery.getJSONObject(i).toString());item.remove("thumbs");list.put(item);}out.putString("json",list.toString());
            }else if("convert".equals(method)){if(arg==null)throw new IllegalArgumentException("path missing");out.putBoolean("accepted",Jobs.convertPath(getContext(),arg));}
            else throw new IllegalArgumentException("Unknown setup command");
        }catch(Exception e){out.putString("error",e.toString());}return out;
    }
    private void dump(android.view.accessibility.AccessibilityNodeInfo node,org.json.JSONArray list)throws Exception{if(node==null||list.length()>1400)return;if(node.isVisibleToUser()){android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);list.put(new org.json.JSONObject().put("text",String.valueOf(node.getText())).put("desc",String.valueOf(node.getContentDescription())).put("id",String.valueOf(node.getViewIdResourceName())).put("class",String.valueOf(node.getClassName())).put("bounds",bounds.flattenToString()).put("clickable",node.isClickable()));}for(int i=0;i<node.getChildCount();i++)dump(node.getChild(i),list);}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order){guard();return null;}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){guard();throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){guard();throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){guard();throw new UnsupportedOperationException();}
}
