package org.telegram.ui.Components;

import android.content.Context;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MangoActivityLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * 「活动」tab 的挂载钩子。
 *
 * SharedMediaLayout 里只插了三条 invoke-static：
 *   1. updateTabs 里 tabs 列表刚创建  → prependTab（插到 0 号位）
 *   2. updateTabs 里 finishAddingTabs 前 → syncTabs（恢复原选中项）
 *   3. switchToCurrentSelectedMode 开头 → schedulePage（挂载页面）
 *
 * 其余全部在这里做，所以 smali 侧零寄存器压力。
 */
public final class MangoActivitiesHook {

    /**
     * 复用 TAB_BOT_PREVIEWS(13)：它只在 user.bot 时出现，活动 tab 只在 !user.bot 时出现，
     * 互斥；而且 13 是这套代码里的合法 id，所有数组下标/守卫链都已按 13 处理过。
     */
    public static final int TAB = 13;
    private static final String LABEL = "活动";

    /** 调试吐司；稳定后改成 false。 */
    private static boolean SHOW_TOAST = true;
    private static boolean toastShown;
    private static final StringBuilder LOG = new StringBuilder();

    private static final WeakHashMap<SharedMediaLayout, MangoActivitiesView> VIEWS = new WeakHashMap<>();
    private static final WeakHashMap<SharedMediaLayout, Integer> PREV_SELECTED = new WeakHashMap<>();
    private static Field fDialogId, fMediaPages, fProfileActivity;
    private static boolean ready;

    private MangoActivitiesHook() {
    }

    private static void init() {
        if (ready) {
            return;
        }
        ready = true;
        try {
            fDialogId = SharedMediaLayout.class.getDeclaredField("dialog_id");
            fDialogId.setAccessible(true);
            fMediaPages = SharedMediaLayout.class.getDeclaredField("mediaPages");
            fMediaPages.setAccessible(true);
            try {
                fProfileActivity = SharedMediaLayout.class.getDeclaredField("profileActivity");
                fProfileActivity.setAccessible(true);
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static long dialogId(SharedMediaLayout layout) {
        init();
        try {
            return fDialogId != null ? fDialogId.getLong(layout) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static SharedMediaLayout.MediaPage[] pages(SharedMediaLayout layout) {
        init();
        try {
            return fMediaPages != null ? (SharedMediaLayout.MediaPage[]) fMediaPages.get(layout) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 资料页是从哪个聊天点进来的？正常流程里资料页上面一层就是 ChatActivity，
     * 那个群的 id 就是最好的锚点来源（TA 大概率在那里发过言）。
     * 拿不到就返回 0。
     */
    private static long parentChatDialogId(SharedMediaLayout layout) {
        try {
            init();
            Object profile = fProfileActivity != null ? fProfileActivity.get(layout) : null;
            if (!(profile instanceof org.telegram.ui.ActionBar.BaseFragment)) {
                return 0;
            }
            org.telegram.ui.ActionBar.INavigationLayout nav =
                    ((org.telegram.ui.ActionBar.BaseFragment) profile).getParentLayout();
            if (nav == null) {
                return 0;
            }
            java.util.List<org.telegram.ui.ActionBar.BaseFragment> stack = nav.getFragmentStack();
            if (stack == null || stack.size() < 2) {
                return 0;
            }
            // 上一层可能是 ChatActivity（从聊天点进来），也可能是群资料页（从成员列表点进来），
            // 两种都反射 dialog_id：负数就是群/频道 id
            for (int i = stack.size() - 2; i >= 0 && i >= stack.size() - 4; i--) {
                long id = reflectDialogId(stack.get(i));
                if (id < 0) {
                    return id;
                }
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    /**
     * 最准的锚点：你现在正待着的那个聊天里，就有一条 TA 发的消息。
     * 从 fragment 栈里把 ChatActivity 捞出来，反射它的 messages 列表，
     * 找到 from_id == 目标用户 的那条，直接拿 [群id, 消息id]。
     */
    public static long[] anchorFromOpenChat(long userId) {
        try {
            if (LaunchActivity.instance == null) {
                return null;
            }
            org.telegram.ui.ActionBar.INavigationLayout nav = LaunchActivity.instance.getActionBarLayout();
            if (nav == null) {
                return null;
            }
            java.util.List<org.telegram.ui.ActionBar.BaseFragment> stack = nav.getFragmentStack();
            if (stack == null) {
                return null;
            }
            for (int i = stack.size() - 1; i >= 0; i--) {
                org.telegram.ui.ActionBar.BaseFragment f = stack.get(i);
                long dialogId = reflectDialogId(f);
                if (dialogId >= 0) {
                    continue;
                }
                Object msgs = reflectField(f, "messages");
                if (!(msgs instanceof java.util.List)) {
                    // 群资料页没有 messages，换下一个
                    continue;
                }
                java.util.List list = (java.util.List) msgs;
                for (int k = list.size() - 1; k >= 0 && k >= list.size() - 300; k--) {
                    Object mo = list.get(k);
                    if (mo == null) {
                        continue;
                    }
                    Object owner = reflectField(mo, "messageOwner");
                    if (!(owner instanceof TLRPC.Message)) {
                        continue;
                    }
                    TLRPC.Message m = (TLRPC.Message) owner;
                    if (m.from_id != null && m.from_id.user_id == userId) {
                        return new long[]{dialogId, m.id};
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static Object reflectField(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 不依赖 SharedMediaLayout：直接从启动页的 fragment 栈里找最近打开的群/频道。 */
    private static long lastGroupDialogIdFromNav() {
        try {
            if (LaunchActivity.instance == null) {
                return 0;
            }
            org.telegram.ui.ActionBar.INavigationLayout nav = LaunchActivity.instance.getActionBarLayout();
            if (nav == null) {
                return 0;
            }
            java.util.List<org.telegram.ui.ActionBar.BaseFragment> stack = nav.getFragmentStack();
            if (stack == null) {
                return 0;
            }
            for (int i = stack.size() - 1; i >= 0; i--) {
                long id = reflectDialogId(stack.get(i));
                if (id < 0) {
                    return id;
                }
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static long reflectDialogId(Object fragment) {
        if (fragment == null) {
            return 0;
        }
        Class<?> c = fragment.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("dialog_id");
                f.setAccessible(true);
                return f.getLong(fragment);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * MessagesController.loadFullUser 的根部拦截：
     * min 用户的 access_hash 是上下文绑定的，直接用会 USER_ID_INVALID。
     * 这里不发送注定失败的请求，直接走 inputUserFromMessage 修复。
     */
    public static void onMinUser(TLRPC.User user) {
        if (user == null) {
            return;
        }
        try {
            hint("正在读取资料，请稍等…");
            long[] anchor = anchorFromOpenChat(user.id);
            if (anchor != null) {
                report("MIN route id=" + user.id + " anchorChat=" + anchor[0] + " msg=" + anchor[1]);
                MangoActivityLoader.repairWithAnchor(UserConfig.selectedAccount, user.id, anchor[0], (int) anchor[1]);
            } else {
                long preferred = lastGroupDialogIdFromNav();
                report("MIN route id=" + user.id + " sourceChat=" + preferred + " (no anchor in open chat)");
                MangoActivityLoader.repairUserFull(UserConfig.selectedAccount, user.id, preferred);
            }
        } catch (Throwable t) {
            report("MIN route throw " + t);
        }
    }

    /** setUserInfo 入口（ProfileActivity 每次刷新资料都会走） */
    public static void onSetUserInfo(SharedMediaLayout layout, Object info) {
        report("HOOK setUserInfo info=" + (info == null ? "null" : "notNull")
                + " did=" + dialogId(layout));
        triggerRepair(layout);
    }

    /** updateTabs 入口 */
    public static void onUpdateTabs(SharedMediaLayout layout, boolean animated) {
        report("HOOK updateTabs did=" + dialogId(layout) + " animated=" + animated);
        triggerRepair(layout);
    }

    /** 统一的触发点：是用户、且本地还没有 userFull，就修 */
    private static void triggerRepair(SharedMediaLayout layout) {
        try {
            long id = dialogId(layout);
            if (id <= 0) {
                report("HOOK skip chat id=" + id);
                return;
            }
            TLRPC.User u = MessagesController.getInstance(UserConfig.selectedAccount).getUser(id);
            report("HOOK user id=" + id + " u=" + (u == null ? "null" : ("min=" + u.min)));
            if (u == null || u.self || u.bot) {
                report("HOOK skip not-user-peer");
                return;
            }
            if (MessagesController.getInstance(UserConfig.selectedAccount).getUserFull(id) != null) {
                report("HOOK already-full id=" + id);
                return;
            }
            MangoActivityLoader.repairUserFull(UserConfig.selectedAccount, id, parentChatDialogId(layout));
        } catch (Throwable t) {
            report("HOOK err " + t);
        }
    }

    private static boolean isUserPeer(long dialogId) {
        if (dialogId <= 0) {
            return false;
        }
        try {
            TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(dialogId);
            return user != null && !user.self && !user.bot;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供 smali 侧 getCurrentListView() 回调：把我们的 RecyclerListView 交给资料页的嵌套滚动逻辑。 */
    public static RecyclerListView getListView(SharedMediaLayout layout) {
        MangoActivitiesView view = layout != null ? VIEWS.get(layout) : null;
        return view != null ? view.getListView() : null;
    }

    private static boolean isActivitiesSelected(SharedMediaLayout layout) {
        try {
            return layout.scrollSlidingTextTabStrip != null
                    && layout.scrollSlidingTextTabStrip.getCurrentTabId() == TAB;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ tab 列表

    /** ① updateTabs 里 tabs 列表刚建好时调用：把「活动」塞到 0 号位（最左边）。 */
    public static void prependTab(SharedMediaLayout layout, ArrayList tabs) {
        if (layout == null || tabs == null) {
            return;
        }
        try {
            if (!isUserPeer(dialogId(layout))) {
                return;
            }
            long earlyDialogId = dialogId(layout);
            if (isUserPeer(earlyDialogId)
                    && MessagesController.getInstance(UserConfig.selectedAccount).getUserFull(earlyDialogId) == null) {
                MangoActivityLoader.repairUserFull(UserConfig.selectedAccount, earlyDialogId);
            }
            for (int i = 0; i < tabs.size(); i++) {
                Object item = tabs.get(i);
                if (item instanceof Pair) {
                    Object first = ((Pair) item).first;
                    if (first instanceof Integer && ((Integer) first).intValue() == TAB) {
                        return;
                    }
                }
            }
            int prev = -1;
            try {
                prev = layout.scrollSlidingTextTabStrip.getCurrentTabId();
            } catch (Throwable ignore) {
            }
            if (prev == TAB) {
                prev = -1;
            }
            tabs.add(0, new Pair(Integer.valueOf(TAB), LABEL));
            PREV_SELECTED.put(layout, Integer.valueOf(prev));
        } catch (Throwable t) {
            FileLog.e(t);
            if (SHOW_TOAST) {
                toast(layout.getContext(), "prependTab: " + t);
            }
        }
    }

    /** ② updateTabs 里 finishAddingTabs() 之前调用：兜底加 tab + 把原选中项还回去。 */
    public static void syncTabs(SharedMediaLayout layout) {
        try {
            if (layout == null || !isUserPeer(dialogId(layout))) {
                return;
            }
            SharedMediaLayout.ScrollSlidingTextTabStripInner strip = layout.scrollSlidingTextTabStrip;
            if (strip == null) {
                return;
            }
            if (!strip.hasTab(TAB)) {
                strip.addTextTab(TAB, LABEL, null);
            }
            // 我们抢了 0 号位，strip 会把 0 号位当默认选中 —— 这里把原来的选中项还回去。
            Integer prev = PREV_SELECTED.remove(layout);
            int target = (prev != null && prev.intValue() > 0) ? prev.intValue() : (strip.hasTab(0) ? 0 : -1);
            if (target >= 0 && target != TAB && strip.hasTab(target) && strip.getCurrentTabId() != target) {
                strip.setInitialTabId(target);
            }
        } catch (Throwable t) {
            FileLog.e(t);
            if (SHOW_TOAST) {
                toast(layout != null ? layout.getContext() : null, "syncTabs: " + t);
            }
        }
    }

    // --------------------------------------------------------------- 页面

    /** ③ switchToCurrentSelectedMode 开头调用：延后一帧挂载，避开切换中间态。 */
    public static void schedulePage(SharedMediaLayout layout) {
        if (layout == null) {
            return;
        }
        layout.post(new Runnable() {
            @Override
            public void run() {
                applyPage(layout);
            }
        });
    }

    private static void applyPage(SharedMediaLayout layout) {
        try {
            SharedMediaLayout.MediaPage[] pages = pages(layout);
            if (pages == null || pages.length == 0) {
                return;
            }
            long dialogId0 = dialogId(layout);
            if (isUserPeer(dialogId0)
                    && MessagesController.getInstance(UserConfig.selectedAccount).getUserFull(dialogId0) == null) {
                // 这个号的本地 User 是 min 用户，普通 getFullUser 会 USER_ID_INVALID；
                // 不管在哪个 tab 都要修，否则资料页永远空白
                MangoActivityLoader.repairUserFull(UserConfig.selectedAccount, dialogId0);
            }
            MangoActivitiesView view = VIEWS.get(layout);
            boolean active = isActivitiesSelected(layout);
            if (!active) {
                if (view != null && view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                return;
            }
            long dialogId = dialogId(layout);
            if (!isUserPeer(dialogId)) {
                return;
            }
            if (view == null) {
                view = new MangoActivitiesView(layout.getContext(), UserConfig.selectedAccount);
                view.setOnMessageClickListener(new MangoActivitiesView.OnMessageClickListener() {
                    @Override
                    public void onMessageClick(long dialogId, int msgId) {
                        openMessage(dialogId, msgId);
                    }
                });
                VIEWS.put(layout, view);
            }
            for (SharedMediaLayout.MediaPage page : pages) {
                if (page == null || page.selectedType != TAB) {
                    continue;
                }
                if (view.getParent() != page) {
                    if (view.getParent() instanceof ViewGroup) {
                        ((ViewGroup) view.getParent()).removeView(view);
                    }
                    // 和 savedMessagesContainer 一样：topMargin = 48 + 8 dp（tab 条高度）
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                    lp.topMargin = org.telegram.messenger.AndroidUtilities.dp(56);
                    page.addView(view, lp);
                    page.bringChildToFront(view);
                }
                view.setPeer(dialogId);
            }
        } catch (Throwable t) {
            FileLog.e(t);
            if (SHOW_TOAST) {
                toast(layout != null ? layout.getContext() : null, "page: " + t);
            }
        }
    }

    /** 诊断用：把 users.getFullUser 的失败原因弹出来。定位完就删。 */
    public static void diag(Object error, Object userObj) {
        try {
            final StringBuilder head = new StringBuilder();
            if (error instanceof TLRPC.TL_error) {
                TLRPC.TL_error e = (TLRPC.TL_error) error;
                head.append("err=").append(e.code).append(' ').append(e.text);
            } else {
                head.append("err=").append(error);
            }
            long uid = 0;
            boolean isMin = false;
            if (userObj instanceof TLRPC.User) {
                TLRPC.User u = (TLRPC.User) userObj;
                uid = u.id;
                isMin = u.min;
                head.append("\nid=").append(u.id)
                    .append("\nhash=").append(u.access_hash)
                    .append("\nhex=").append(Long.toHexString(u.access_hash))
                    .append("\nmin=").append(u.min)
                    .append(" self=").append(u.self)
                    .append(" deleted=").append(u.deleted)
                    .append(" bot=").append(u.bot)
                    .append(" contact=").append(u.contact)
                    .append("\nflags=").append(u.flags)
                    .append("\nuname=").append(u.username)
                    .append(" first=").append(u.first_name)
                    .append(" phoneLen=").append(u.phone == null ? -1 : u.phone.length());
                try {
                    int acc = UserConfig.selectedAccount;
                    head.append("\ndc=").append(org.telegram.tgnet.ConnectionsManager.getInstance(acc).getCurrentDatacenterId());
                    head.append("\nfullCached=").append(MessagesController.getInstance(acc).getUserFull(u.id) != null);
                } catch (Throwable ignore) {
                }
            }

            // 先发起修复（min 用户才会走），再延迟 3 秒把「头部 + 修复日志」一起复制，
            // 这样 repair 链路的异步日志也能落进同一次粘贴里。
            if (isMin) {
                final long targetId = uid;
                final int acc = UserConfig.selectedAccount;
                report("diag -> repairUserFull id=" + targetId);
                try {
                    MangoActivityLoader.repairUserFull(acc, targetId);
                } catch (Throwable t) {
                    report("repair throw " + t);
                }
            }

            FileLog.e("MangoDiag getFullUser -> " + head);
        } catch (Throwable ignore) {
        }
    }

    /** 把修复链路的中间结果累积起来，最后跟错误详情一起复制。 */
    public static void report(final String text) {
        try {
            synchronized (LOG) {
                LOG.append("R: ").append(text).append('\n');
                if (LOG.length() > 12000) {
                    LOG.delete(0, LOG.length() - 8000);
                }
            }
            FileLog.e("MangoRepair -> " + text);
        } catch (Throwable ignore) {
        }
    }

    /** 给用户看的轻提示（不走日志通道）。 */
    public static void hint(final String text) {
        try {
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static void toast(Context context, String text) {
        try {
            if (context != null) {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignore) {
        }
    }

    private static void openMessage(long dialogId, int msgId) {
        try {
            if (LaunchActivity.instance != null) {
                LaunchActivity.instance.getActionBarLayout().presentFragment(ChatActivity.of(dialogId, msgId));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
