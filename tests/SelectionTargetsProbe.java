package com.stickerbridge.app;

public final class SelectionTargetsProbe {
    private static final String TEXT = "android.widget.TextView";
    private static final String EDIT = "android.widget.EditText";
    private static final String BUTTON = "android.widget.Button";
    private static final String VIEW = "android.view.View";

    public static void main(String[] args) {
        require(SelectionTargets.isCommentPrompt(TEXT, null, "期待你的评论", null),
                "Alternate Douyin 41 composer placeholder");
        require(SelectionTargets.isCommentPrompt(TEXT, null, "发条评论", null),
                "The reported TextView composer should establish comment scope");
        require(SelectionTargets.isCommentPrompt(TEXT, null, "发条评论，和大家一起讨论", null),
                "The labelled TextView composer should establish comment scope");
        require(SelectionTargets.isCommentPrompt(EDIT, "说点什么…", null, null),
                "The existing editable composer should still establish comment scope");
        require(!SelectionTargets.isCommentPrompt(TEXT, null, "评论 5011", null),
                "A comment count is not a composer");
        require(!SelectionTargets.isCommentPrompt(TEXT, null, "这条评论很好笑", null),
                "Ordinary comment body is not a composer");
        require(!SelectionTargets.isCommentPrompt(TEXT, null, "我想发条评论，和大家一起讨论", null),
                "A composer phrase inside a comment body is insufficient");
        require(!SelectionTargets.isCommentPrompt(BUTTON, null, "发条评论", null),
                "A button label is not a composer");
        String tab = "androidx.appcompat.app.ActionBar$Tab";
        require(SelectionTargets.isSelectedCommentTab(tab, true, "评论 3796"), "Selected comment tab");
        require(SelectionTargets.isSelectedCommentTab(tab, true, "评论 2.8万"), "Compact comment count");
        require(!SelectionTargets.isSelectedCommentTab(tab, false, "评论 3796"), "Inactive tab");
        require(!SelectionTargets.isSelectedCommentTab(BUTTON, true, "评论 3796"), "Feed counter button");
        require(!SelectionTargets.isSelectedCommentTab(tab, true, "评论是这样的"), "Not an exact tab label");
        require(SelectionTargets.isReplyLabel("回复") && SelectionTargets.isReplyLabel("展开68条回复"),
                "Comment row markers");
        require(!SelectionTargets.isReplyLabel("可以回复我吗"), "Unrelated message");
        require(SelectionTargets.followsCommentTab(920, 922, 1080, 1080, 3f), "Observed panel geometry");
        require(!SelectionTargets.followsCommentTab(920, 0, 1080, 1080, 3f), "Feed behind the panel");
        require(!SelectionTargets.followsCommentTab(920, 922, 300, 1080, 3f), "Narrow tile");
        require(!SelectionTargets.followsCommentTab(920, 1250, 1080, 1080, 3f), "Detached container");
        require(SelectionTargets.composeMediaLeaf(VIEW, null, null, 0, true, true),
                "An anonymous Compose leaf can carry media within comment scope");
        require(!SelectionTargets.composeMediaLeaf(VIEW, null, null, 0, true, false),
                "An anonymous Compose leaf outside comments is not a media candidate");
        require(!SelectionTargets.composeMediaLeaf(VIEW, null, null, 0, false, true),
                "An anonymous traditional View is not inferred as Compose media");
        require(!SelectionTargets.composeMediaLeaf(VIEW, "昵称", null, 0, true, true),
                "A text-bearing leaf is not inferred as Compose media");
        require(!SelectionTargets.composeMediaLeaf(VIEW, null, null, 1, true, true),
                "A blank container is not a media leaf");
        System.out.println("SelectionTargets comment scope regression passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
