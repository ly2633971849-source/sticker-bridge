package com.stickerbridge.app;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounds are always in physical screen coordinates, never overlay-local coordinates. */
public final class SelectionTargets {
    private static final Pattern COMMENT_TAB = Pattern.compile("评论(?:\\s*[0-9]+(?:[.,][0-9]+)?\\s*[万亿]?[+]?)?");
    private static final Pattern EXPAND_REPLIES = Pattern.compile("展开\\s*[0-9]+\\s*条回复");
    public static final class Target {
        public final Rect bounds;
        public final Rect nodeBounds;
        public final String id, description;
        public final int rank;
        public boolean previewViewport,animationExpected;
        Target(Rect b,Rect node,String id,String description,int rank){this.bounds=new Rect(b);this.nodeBounds=new Rect(node);this.id=id;this.description=description;this.rank=rank;this.animationExpected=description.contains("视频")||description.contains("动图")||description.contains("实况");}
    }
    public static List<Target> collect(AccessibilityNodeInfo root,float density,int screenWidth,int screenHeight){
        ArrayList<Target> out=new ArrayList<>();int[] visited={0};
        // A preview may expose the comment list behind it: inspect only its foreground subtree.
        List<AccessibilityNodeInfo> previews=new ArrayList<>();if(root!=null)previews.addAll(root.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.aweme:id/x-3"));
        if(root!=null&&previews.isEmpty())for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("评论图片详情页"))if(node.isVisibleToUser()&&String.valueOf(node.getContentDescription()).contains("图片详情页"))previews.add(node);
        boolean im=isIm(root);
        if(previews.isEmpty()){
            AccessibilityNodeInfo commentBody=findCommentBody(root,density,screenWidth);
            if(commentBody!=null)walk(commentBody,density,screenWidth,screenHeight,out,visited,false,true,true);
            else walk(root,density,screenWidth,screenHeight,out,visited,im,hasCommentInput(root,new int[]{0}),false);
        }else for(AccessibilityNodeInfo preview:previews)walk(preview,density,screenWidth,screenHeight,out,visited,im,true,false);
        if(!previews.isEmpty()){
            boolean animated=hasExactText(previews.get(0),"动图")||hasExactText(previews.get(0),"实况");
            if(out.isEmpty()){
                AccessibilityNodeInfo host=previewHost(previews.get(0),screenWidth,screenHeight,new int[]{0});if(host==null)host=previews.get(0);Rect raw=new Rect();host.getBoundsInScreen(raw);Rect b=visibleBounds(2,raw,density,screenWidth,screenHeight);
                if(b!=null){Target fallback=new Target(b,raw,String.valueOf(host.getViewIdResourceName()),String.valueOf(host.getContentDescription()),2);fallback.previewViewport=true;fallback.animationExpected=animated;out.add(fallback);}
            }else for(Target target:out)target.animationExpected|=animated;
        }
        for(int i=out.size()-1;i>=0;i--){Target parent=out.get(i);boolean nested=false;for(Target child:out)if(child!=parent&&child.rank<=parent.rank&&parent.bounds.contains(child.bounds)&&!parent.bounds.equals(child.bounds)){nested=true;break;}if(nested)out.remove(i);}
        return out;
    }
    private static boolean hasExactText(AccessibilityNodeInfo root,String text){for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.isVisibleToUser()&&(text.contentEquals(String.valueOf(node.getText()))||text.contentEquals(String.valueOf(node.getContentDescription()))))return true;return false;}
    private static AccessibilityNodeInfo previewHost(AccessibilityNodeInfo node,int width,int height,int[] visited){
        if(node==null||visited[0]++>300)return null;AccessibilityNodeInfo best=null;Rect b=new Rect();node.getBoundsInScreen(b);String cls=String.valueOf(node.getClassName());
        if(node.isVisibleToUser()&&node.getChildCount()==0&&(cls.contains("FrameLayout")||cls.contains("SurfaceView")||cls.contains("TextureView"))&&b.width()>width*.5&&b.height()>height*.35)best=node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=previewHost(node.getChild(i),width,height,visited);if(child!=null){Rect childBounds=new Rect();child.getBoundsInScreen(childBounds);if(best==null||(long)childBounds.width()*childBounds.height()<(long)b.width()*b.height()){best=child;b.set(childBounds);}}}return best;
    }
    public static int stickerRank(String id,String desc,boolean im){
        if(id.contains("avatar")||id.endsWith("/iv_image")||id.endsWith("/n6b")||desc.contains("按钮")||desc.contains("插入"))return 99;
        if(desc.contains("图片表情")||desc.contains("自定义表情")||id.endsWith("/kv+"))return 0;
        if(im&&(id.endsWith("/dx0")||id.endsWith("/fs=")))return 1;
        return 99;
    }
    /** Positive media semantics survive resource-ID changes; avatars and composer controls never qualify. */
    public static int mediaRank(String id,String desc,String cls,boolean im,boolean comments){
        if(!mediaClassAllowed(cls)||id.contains("avatar")||desc.contains("头像")||desc.contains("按钮")||desc.contains("插入")||id.endsWith("/iv_image")||id.endsWith("/n6b"))return 99;
        int sticker=stickerRank(id,desc,im);if(sticker<99)return sticker;
        boolean image=cls.contains("ImageView")||cls.contains("DraweeView")||cls.contains("PhotoView")||cls.contains("VideoView")||cls.contains("TextureView");
        if(!image)return 99;
        if(id.endsWith("/jo-")||id.endsWith("/abb"))return 2;
        if(desc.equals("图片")||desc.equals("查看图片")||desc.equals("查看原图")||desc.equals("动图")||desc.equals("实况图片")||desc.equals("视频")||desc.equals("小视频")||desc.equals("播放视频"))return 2;
        if((comments||im)&&cls.contains("PhotoView"))return 2;
        if((comments||im)&&(desc.startsWith("图片 ")||desc.matches("图片[0-9]+.*")))return 3;
        return 99;
    }
    public static boolean isImInput(String cls,String hint,String text,String description){
        if(!cls.contains("EditText"))return false;
        String prompt=hint==null||hint.isEmpty()||hint.equals("null")?String.valueOf(text)+" "+String.valueOf(description):hint;
        prompt=prompt.toLowerCase(java.util.Locale.ROOT);
        return prompt.contains("发送消息")||prompt.contains("发消息")||prompt.contains("send message")||prompt.contains("send a message")||prompt.trim().equals("message");
    }
    public static boolean isIm(AccessibilityNodeInfo root){
        if(root==null)return false;
        if(!root.findAccessibilityNodeInfosByViewId("com.ss.android.ugc.aweme:id/msg_et").isEmpty())return true;
        return findImInput(root,new int[]{0});
    }
    private static boolean findImInput(AccessibilityNodeInfo n,int[] visited){
        if(n==null||visited[0]++>1400)return false;
        if(n.isVisibleToUser()&&isImInput(String.valueOf(n.getClassName()),String.valueOf(n.getHintText()),String.valueOf(n.getText()),String.valueOf(n.getContentDescription())))return true;
        for(int i=0;i<n.getChildCount();i++)if(findImInput(n.getChild(i),visited))return true;return false;
    }
    private static boolean hasCommentInput(AccessibilityNodeInfo n,int[] visited){
        if(n==null||visited[0]++>1400)return false;
        if(n.isVisibleToUser()&&isCommentPrompt(String.valueOf(n.getClassName()),String.valueOf(n.getHintText()),String.valueOf(n.getText()),String.valueOf(n.getContentDescription())))return true;
        for(int i=0;i<n.getChildCount();i++)if(hasCommentInput(n.getChild(i),visited))return true;return false;
    }
    static boolean isSelectedCommentTab(String cls,boolean selected,String label){
        return selected&&(cls.endsWith("$Tab")||cls.endsWith(".Tab"))&&label!=null&&COMMENT_TAB.matcher(label.trim()).matches();
    }
    static boolean isReplyLabel(String label){
        return label!=null&&(label.trim().equals("回复")||EXPAND_REPLIES.matcher(label.trim()).matches());
    }
    static boolean followsCommentTab(int headerBottom,int bodyTop,int bodyWidth,int screenWidth,float density){
        return bodyTop>=headerBottom&&bodyTop-headerBottom<=48*density&&bodyWidth>=screenWidth*.6f;
    }
    private static AccessibilityNodeInfo findCommentBody(AccessibilityNodeInfo root,float density,int screenWidth){
        if(root==null)return null;
        for(AccessibilityNodeInfo title:root.findAccessibilityNodeInfosByText("评论")){
            if(!title.isVisibleToUser())continue;
            String label=title.getText()!=null?title.getText().toString():String.valueOf(title.getContentDescription());
            if(!COMMENT_TAB.matcher(label.trim()).matches())continue;
            AccessibilityNodeInfo tab=title;
            for(int depth=0;tab!=null&&depth<4&&!isSelectedCommentTab(String.valueOf(tab.getClassName()),tab.isSelected(),label);depth++)tab=tab.getParent();
            if(tab==null||!isSelectedCommentTab(String.valueOf(tab.getClassName()),tab.isSelected(),label))continue;
            Rect header=new Rect();tab.getBoundsInScreen(header);
            AccessibilityNodeInfo owner=tab.getParent();
            for(int depth=0;owner!=null&&depth<6&&!owner.equals(root);depth++,owner=owner.getParent()){
                AccessibilityNodeInfo body=findCommentCompose(owner,header.bottom,density,screenWidth,new int[]{0});
                if(body!=null)return body;
            }
        }
        return null;
    }
    private static AccessibilityNodeInfo findCommentCompose(AccessibilityNodeInfo node,int headerBottom,float density,int screenWidth,int[] visited){
        if(node==null||visited[0]++>=350)return null;
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        if(bounds.bottom<=headerBottom)return null;
        if(node.isVisibleToUser()&&String.valueOf(node.getClassName()).contains("ComposeView")
                &&followsCommentTab(headerBottom,bounds.top,bounds.width(),screenWidth,density)
                &&hasReplyRow(node,bounds,new int[]{0}))return node;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo body=findCommentCompose(node.getChild(i),headerBottom,density,screenWidth,visited);
            if(body!=null)return body;
        }
        return null;
    }
    private static boolean hasReplyRow(AccessibilityNodeInfo node,Rect body,int[] visited){
        if(node==null||visited[0]++>=350)return false;
        if(node.isVisibleToUser()&&(isReplyLabel(String.valueOf(node.getText()))||isReplyLabel(String.valueOf(node.getContentDescription())))){
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            if(!bounds.isEmpty()&&body.contains(bounds))return true;
        }
        for(int i=0;i<node.getChildCount();i++)if(hasReplyRow(node.getChild(i),body,visited))return true;
        return false;
    }
    static boolean isCommentPrompt(String cls,String hint,String text,String description){
        String prompt=hint==null||hint.isEmpty()||hint.equals("null")?text:hint;
        if(cls.contains("EditText"))return prompt!=null&&(prompt.contains("评论")||prompt.contains("说点什么"));
        if(!cls.equals("android.widget.TextView")&&!cls.equals("android.view.View"))return false;
        return commentPlaceholder(text)||commentPlaceholder(description);
    }
    private static boolean commentPlaceholder(String label){
        if(label==null)return false;
        String prompt=label.trim();
        return prompt.equals("发条评论")||prompt.equals("写评论")||prompt.equals("期待你的评论")
                ||prompt.equals("发条评论，和大家一起讨论")||prompt.equals("发条评论,和大家一起讨论");
    }
    public static boolean mediaClassAllowed(String cls){return !cls.endsWith("Button")&&!cls.endsWith("CheckBox")&&!cls.contains("EditText");}
    public static Rect visibleBounds(int rank,Rect original,float density,int width,int height){
        if(rank>=99)return null;Rect b=new Rect(original);
        if(!b.intersect(0,0,width,height)||b.width()<density*24||b.height()<density*24)return null;
        // Explicit sticker labels can cover the whole screen. Generic IM containers retain the guard.
        if(rank!=0&&rank!=2&&(long)b.width()*b.height()>=(long)width*height*.85)return null;
        return b;
    }
    private static Rect imageChild(AccessibilityNodeInfo n,Rect marker,float density,int width,int height,int[] visited){
        if(n==null||visited[0]++>=48)return null;Rect best=null;
        String cls=String.valueOf(n.getClassName()),id=String.valueOf(n.getViewIdResourceName()),desc=String.valueOf(n.getContentDescription());
        if(!mediaClassAllowed(cls))return null;
        if(n.isVisibleToUser()&&(cls.contains("ImageView")||cls.contains("DraweeView")||cls.contains("PhotoView"))&&!id.contains("avatar")&&!id.endsWith("/iv_image")&&!id.endsWith("/n6b")&&!desc.contains("头像")&&!desc.contains("插入")&&!desc.contains("按钮")){
            Rect raw=new Rect();n.getBoundsInScreen(raw);Rect b=visibleBounds(0,raw,density,width,height);
            if(b!=null&&marker.contains(b)&&(long)b.width()*b.height()>=(long)marker.width()*marker.height()*.35)best=b;
        }
        for(int i=0;i<n.getChildCount();i++){Rect child=imageChild(n.getChild(i),marker,density,width,height,visited);if(child!=null&&(best==null||(long)child.width()*child.height()>(long)best.width()*best.height()))best=child;}
        return best;
    }
    public static boolean composeMediaLeaf(String cls,String text,String desc,int children,boolean compose,boolean comments){
        return compose&&comments&&cls.equals("android.view.View")&&children==0&&(text==null||text.isEmpty()||text.equals("null"))&&(desc==null||desc.isEmpty()||desc.equals("null"));
    }
    private static void walk(AccessibilityNodeInfo n,float density,int width,int height,List<Target> out,int[] visited,boolean im,boolean comments,boolean compose){
        if(n==null||visited[0]++>1400)return;
        compose=compose||String.valueOf(n.getClassName()).contains("ComposeView");
        if(!mediaClassAllowed(String.valueOf(n.getClassName())))return;
        if(n.isVisibleToUser()){
            String id=String.valueOf(n.getViewIdResourceName()),desc=String.valueOf(n.getContentDescription()),cls=String.valueOf(n.getClassName());
            Rect raw=new Rect();n.getBoundsInScreen(raw);
            int rank=mediaRank(id,desc,cls,im,comments);
            if(rank>=99&&composeMediaLeaf(cls,String.valueOf(n.getText()),desc,n.getChildCount(),compose,comments))rank=3;
            Rect node=visibleBounds(rank,raw,density,width,height);
            if(rank>=2&&node!=null&&(node.width()<density*64||node.height()<density*64))node=null;
            if(node!=null){
                Rect child=imageChild(n,node,density,width,height,new int[]{0}),b=child==null?node:child;
                boolean duplicate=false;for(int i=0;i<out.size();i++)if(out.get(i).bounds.equals(b)){if(out.get(i).rank>rank)out.set(i,new Target(b,raw,id,desc,rank));duplicate=true;break;}
                if(!duplicate)out.add(new Target(b,raw,id,desc,rank));
            }
        }
        for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),density,width,height,out,visited,im,comments,compose);
    }
    public static Target at(List<Target> targets,int x,int y){
        Target best=null;for(Target t:targets)if(t.bounds.contains(x,y)&&(best==null||t.rank<best.rank||(t.rank==best.rank&&(long)t.bounds.width()*t.bounds.height()<(long)best.bounds.width()*best.bounds.height())))best=t;return best;
    }
}
