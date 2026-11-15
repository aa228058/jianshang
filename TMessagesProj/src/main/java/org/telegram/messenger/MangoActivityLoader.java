package org.telegram.messenger;

import android.text.TextUtils;
import android.util.LongSparseArray;
import android.util.SparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「活动」数据层。
 *
 * 逻辑只有两步：
 *   1) messages.getCommonChats(peer, 0, 100)      —— 我和 TA 的共同群
 *   2) 对每个群 messages.search(from_id = TA)      —— TA 在那个群里说过的话
 *
 * 全程只读、纯 MTProto、不依赖任何 UI，所以前台后台都能跑。
 */
public final class MangoActivityLoader {

    /** 一条活动记录。 */
    public static final class Item {
        public final int msgId;
        /** 直接可以喂给 ChatActivity.of() 的对话 id（= -chat.id）。 */
        public final long dialogId;
        /** unix 秒。 */
        public final long date;
        public final String groupName;
        public final String text;

        Item(int msgId, long dialogId, long date, String groupName, String text) {
            this.msgId = msgId;
            this.dialogId = dialogId;
            this.date = date;
            this.groupName = groupName;
            this.text = text;
        }
    }

    public interface Callback {
        void onLoaded(int account, long peerUserId, ArrayList<Item> items, boolean fromCache);
    }

    /** 共同群最多扫这么多个。原来 100 个 × 50 条会在十几秒内打上百个
     *  messages.search，直接触发账号级别的 FLOOD_WAIT —— 一旦被限流，
     *  图片/动图/头像的 upload.getFile 也会一起卡死。 */
    private static final int MAX_GROUPS = 20;
    private static final int PER_GROUP_LIMIT = 30;
    /** Nicegram 原版是 100 个 search 一次性全并发（所以它快，但一把梭会撞账号级限流）。
     *  这里改成「小批并发」：同时最多 3 个在飞，每完成一个隔 320ms 再补一个。
     *  20 个群大约 2~3 秒出结果，又不至于把账号打进 FLOOD_WAIT。 */
    private static final int CONCURRENCY = 3;
    private static final long BATCH_GAP_MS = 320L;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    /** 撞到 FLOOD 之后静默这么久，期间不发任何 search。 */
    private static final long FLOOD_COOLDOWN_MS = 20 * 60 * 1000L;
    private static final long[] floodUntil = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private static final class Entry {
        final int account;
        final long ts;
        final ArrayList<Item> items;

        Entry(int account, ArrayList<Item> items) {
            this.account = account;
            this.items = items;
            this.ts = System.currentTimeMillis();
        }
    }

    private static final LongSparseArray<Entry> cache = new LongSparseArray<>();
    private static final SparseArray<ArrayList<Long>> inFlight = new SparseArray<>();

    private MangoActivityLoader() {
    }

    /** 这个账号是不是还在限流冷却期里。 */
    public static boolean isFlooded(int account) {
        return account >= 0 && account < floodUntil.length
                && System.currentTimeMillis() < floodUntil[account];
    }

    /** 还剩多少毫秒。 */
    public static long floodRemainingMs(int account) {
        if (account < 0 || account >= floodUntil.length) {
            return 0L;
        }
        return Math.max(0L, floodUntil[account] - System.currentTimeMillis());
    }

    private static void markFlooded(int account) {
        if (account >= 0 && account < floodUntil.length) {
            floodUntil[account] = System.currentTimeMillis() + FLOOD_COOLDOWN_MS;
            FileLog.d("MangoActivityLoader: FLOOD_WAIT, cooldown " + (FLOOD_COOLDOWN_MS / 60000) + " min");
        }
    }

    public static void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /** 命中且没过期就返回，否则 null。 */
    public static ArrayList<Item> cached(long peerUserId, int account) {
        synchronized (cache) {
            Entry e = cache.get(peerUserId);
            if (e == null || e.account != account) {
                return null;
            }
            if (System.currentTimeMillis() - e.ts > CACHE_TTL_MS) {
                return null;
            }
            return e.items;
        }
    }

    public static void load(final int account, final long peerUserId, boolean force, final Callback callback) {
        if (peerUserId == 0) {
        if (callback != null) {
                callback.onLoaded(account, peerUserId, new ArrayList<>(), false);
            }
            return;
        }
        if (!force) {
            ArrayList<Item> hit = cached(peerUserId, account);
            if (hit != null) {
            if (callback != null) {
                    callback.onLoaded(account, peerUserId, hit, true);
                }
                return;
            }
        }
        if (isFlooded(account)) {
        if (callback != null) {
                callback.onLoaded(account, peerUserId, cached(peerUserId, account) != null
                        ? cached(peerUserId, account) : new ArrayList<>(), true);
            }
            return;
        }
        // 同一个人的请求并发去重
        ArrayList<Long> pending = inFlight.get(account);
        if (pending == null) {
            pending = new ArrayList<>();
            inFlight.put(account, pending);
        }
        if (pending.contains(peerUserId)) {
            return;
        }
        pending.add(peerUserId);

        final MessagesController mc = MessagesController.getInstance(account);
        MessagesController.CommonChatsList known = mc.getCommonChats(peerUserId, false);
        if (known != null && !known.chats.isEmpty()) {
            searchAll(account, peerUserId, new ArrayList<>(known.chats), callback);
            return;
        }

        TLRPC.TL_messages_getCommonChats req = new TLRPC.TL_messages_getCommonChats();
        req.user_id = mc.getInputUser(peerUserId);
        req.max_id = 0;
        req.limit = MAX_GROUPS;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            final ArrayList<TLRPC.Chat> groups = new ArrayList<>();
            if (res instanceof TLRPC.messages_Chats) {
                TLRPC.messages_Chats chats = (TLRPC.messages_Chats) res;
                mc.putChats(chats.chats, false);
                groups.addAll(chats.chats);
            }
            AndroidUtilities.runOnUIThread(() -> searchAll(account, peerUserId, groups, callback));
        });
    }

    private static void searchAll(final int account, final long peerUserId,
                                  final ArrayList<TLRPC.Chat> groups, final Callback callback) {
        final ArrayList<Item> out = new ArrayList<>();
        if (groups.isEmpty()) {
            finish(account, peerUserId, out, callback);
            return;
        }
        final int total = Math.min(groups.size(), MAX_GROUPS);
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        final Runnable[] pump = new Runnable[1];

        pump[0] = new Runnable() {
            @Override
            public void run() {
                if (isFlooded(account)) {
                    if (active.get() == 0) {
                        Collections.sort(out, (a, b) -> Long.compare(b.date, a.date));
                        finish(account, peerUserId, out, callback);
                    }
                    return;
                }
                while (active.get() < CONCURRENCY && next.get() < total) {
                    final TLRPC.Chat chat = groups.get(next.getAndIncrement());
                    active.incrementAndGet();
                    searchOne(account, peerUserId, chat, out, new Runnable() {
                        @Override
                        public void run() {
                            active.decrementAndGet();
                            final int finished = done.incrementAndGet();
                            if (finished >= total) {
                                Collections.sort(out, (a, b) -> Long.compare(b.date, a.date));
                                finish(account, peerUserId, out, callback);
                            } else {
                                // 每跑完一批就先吐一次结果，别让用户干等
                                if (finished % CONCURRENCY == 0 && out.size() > 0) {
                                    final ArrayList<Item> snapshot;
                                    synchronized (out) {
                                        snapshot = new ArrayList<>(out);
                                    }
                                    Collections.sort(snapshot, (a, b) -> Long.compare(b.date, a.date));
                                    final ArrayList<Item> partial = snapshot;
                                    AndroidUtilities.runOnUIThread(() ->
                                            callback.onLoaded(account, peerUserId, partial, true));
                                }
                                Utilities.globalQueue.postRunnable(pump[0], BATCH_GAP_MS);
                            }
                        }
                    });
                }
            }
        };
        Utilities.globalQueue.postRunnable(pump[0], 0);
    }

    private static void searchOne(int account, long peerUserId, TLRPC.Chat chat,
                                  ArrayList<Item> out, Runnable done) {
        try {
            TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = MessagesController.getInputPeer(chat);
            req.from_id = MessagesController.getInstance(account).getInputPeer(peerUserId);
            if (req.from_id != null) {
                req.flags |= 1;
            }
            req.q = "";
            req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            req.offset_id = 0;
            req.limit = PER_GROUP_LIMIT;
            req.min_date = 0;
            req.max_date = 0;
            final long dialogId = -chat.id;
            final String title = TextUtils.isEmpty(chat.title) ? "" : chat.title;

            ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
                if (err != null && (err.code == 420 || (err.text != null && err.text.startsWith("FLOOD")))) {
                    markFlooded(account);
                }
                if (res instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages mm = (TLRPC.messages_Messages) res;
                    // 把服务端返回的 users 灌回去：如果本地缓存的 access_hash 过期了
                    // （表现为 getFullUser -> USER_ID_INVALID），这里能顺手刷新掉
                    try {
                        if (mm.users != null && !mm.users.isEmpty()) {
                            MessagesController.getInstance(account).putUsers(mm.users, false);
                        }
                        if (mm.chats != null && !mm.chats.isEmpty()) {
                            MessagesController.getInstance(account).putChats(mm.chats, false);
                        }
                    } catch (Throwable ignore) {
                    }
                    synchronized (out) {
                        for (int i = 0; i < mm.messages.size(); i++) {
                            TLRPC.Message m = (TLRPC.Message) mm.messages.get(i);
                            if (m == null || m instanceof TLRPC.TL_messageService || m instanceof TLRPC.TL_messageEmpty) {
                                continue;
                            }
                            if (TextUtils.isEmpty(m.message)) {
                                continue;
                            }
                            out.add(new Item(m.id, dialogId, m.date, title, m.message));
                        }
                    }
                }
                done.run();
            });
        } catch (Throwable t) {
            FileLog.e(t);
            done.run();
        }
    }

    private static void finish(int account, long peerUserId, ArrayList<Item> items, Callback callback) {
        synchronized (cache) {
            cache.put(peerUserId, new Entry(account, items));
        }
        ArrayList<Long> pending = inFlight.get(account);
        if (pending != null) {
            pending.remove(Long.valueOf(peerUserId));
        }
        repairFullUser(account, peerUserId, items);
        if (callback != null) {
            // 这个回调是从 ConnectionsManager 的 stageQueue 里出来的，必须回主线程再碰 View
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(account, peerUserId, items, false));
        }
    }

    /**
     * 有些号的 access_hash 在本地缓存里已经失效，users.getFullUser 会返回 USER_ID_INVALID，
     * 资料页就一片空白。这里用「他在某个群里发过的某条消息」当锚点，走
     * inputUserFromMessage —— 这条路不吃 access_hash，是一个合法的兜底。
     * 拿到结果后把 userFull 塞回 MessagesController.fullUsers 并广播，资料页会立刻刷新。
     */
    private static void repairFullUser(final int account, final long peerUserId, ArrayList<Item> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            Item anchor = items.get(0);
            MessagesController mc = MessagesController.getInstance(account);
            sendRepair(account, peerUserId, mc.getInputPeer(anchor.dialogId), anchor.msgId, null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static final java.util.HashSet<Long> repairing = new java.util.HashSet<>();
    /** 已经修过的号，避免重复扫（ProfileActivity 会反复 setUserInfo）。 */
    private static final java.util.HashSet<Long> repaired = new java.util.HashSet<>();

    /**
     * 本地 User 是 min 用户（min=true）时，它的 access_hash 只能在原上下文用，
     * 直接 users.getFullUser 会 USER_ID_INVALID → 资料页空白。
     *
     * 这里用「上下文锚点」走 inputUserFromMessage，全程只读、不需要发消息：
     *   ① 和 TA 的私聊里最后一条消息
     *   ② 找不到私聊就用共同群：messages.getCommonChats → messages.search(from_id=TA, limit=1)
     *      拿一条 TA 发过的消息，用「群 + 消息 id」当锚点
     */
    public static void repairUserFull(final int account, final long userId) {
        repairUserFull(account, userId, 0);
    }

    public static void repairUserFull(final int account, final long userId, final long preferredDialogId) {
        final MessagesController mc = MessagesController.getInstance(account);
        if (mc.getUserFull(userId) != null) {
            return;
        }
        synchronized (repairing) {
            if (repairing.contains(userId) || repaired.contains(userId)) {
                return;
            }
            repairing.add(userId);
        }
        final Runnable giveUp = () -> {
            synchronized (repairing) {
                repairing.remove(userId);
            }
        };

        // ① 私聊锚点
        TLRPC.Dialog dialog = mc.getDialog(userId);
        if (dialog != null && dialog.top_message != 0) {
            sendRepair(account, userId, mc.getInputPeer(userId), dialog.top_message,
                    () -> repairByCommonChats(account, userId, preferredDialogId, giveUp));
            return;
        }
        repairByCommonChats(account, userId, preferredDialogId, giveUp);
    }

    /**
     * ② 找锚点。分三轮，全部只读：
     *   A. 来源群（你是从哪个群进资料页的）—— 翻最多 5 页历史（500 条）
     *   B. 私聊最后一条
     *   C. 其余群会话，最多 40 个，每批 6 个并发，每群翻 100 条
     * 找不到再放弃；找到就用 inputUserFromMessage 换完整资料。
     */
    private static void repairByCommonChats(final int account, final long userId,
                                           long preferredDialogId, final Runnable giveUp) {
        final MessagesController mc = MessagesController.getInstance(account);
        final java.util.ArrayList<Long> others = new java.util.ArrayList<>();
        try {
            androidx.collection.LongSparseArray dialogs = mc.dialogs_dict;
            for (int i = 0; i < dialogs.size() && others.size() < 40; i++) {
                TLRPC.Dialog d = (TLRPC.Dialog) dialogs.valueAt(i);
                if (d == null || d.id >= 0 || d instanceof TLRPC.TL_dialogFolder) {
                    continue;
                }
                if (!DialogObject.isChatDialog(d.id) || d.id == preferredDialogId) {
                    continue;
                }
                others.add(d.id);
            }
        } catch (Throwable ignore) {
        }

        report("REPAIR start id=" + userId + " preferred=" + preferredDialogId + " others=" + others.size());

        if (preferredDialogId != 0) {
            scanChatDeep(account, userId, preferredDialogId, 60, () -> scanRest(account, userId, others, giveUp));
        } else {
            scanRest(account, userId, others, giveUp);
        }
    }

    /** 全局截止时间：一直翻也不能翻一整天 */
    private static volatile long scanDeadline = 0L;

    /** 深翻一个群的历史：一页一页往前，直到找到或翻完（也有 60 页 / 90 秒上限）。 */
    private static void scanChatDeep(final int account, final long userId, final long dialogId,
                                     final int pages, final Runnable onMiss) {
        if (scanDeadline == 0L) {
            scanDeadline = System.currentTimeMillis() + 90_000L;
        }
        scanPage(account, userId, dialogId, 0, pages, onMiss);
    }

    private static void scanPage(final int account, final long userId, final long dialogId,
                                 final int page, final int maxPages, final Runnable onMiss) {
        final MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
        history.peer = mc.getInputPeer(dialogId);
        history.offset_id = 0;
        history.offset_date = 0;
        history.add_offset = 0;
        history.limit = 100;
        history.max_id = 0;
        history.min_id = 0;
        if (page > 0) {
            // 用上一页最老的一条当 offset，继续往前翻
            history.offset_id = lastOffsetOf(dialogId);
        }
        ConnectionsManager.getInstance(account).sendRequest(history, (res3, err3) -> {
            int foundId = 0;
            int oldestId = 0;
            if (res3 instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages mm = (TLRPC.messages_Messages) res3;
                for (int i = 0; i < mm.messages.size(); i++) {
                    TLRPC.Message m = (TLRPC.Message) mm.messages.get(i);
                    if (m == null) {
                        continue;
                    }
                    if (oldestId == 0 || m.id < oldestId) {
                        oldestId = m.id;
                    }
                    if (m.from_id != null && m.from_id.user_id == userId) {
                        foundId = m.id;
                        break;
                    }
                }
            }
            if (foundId != 0) {
                report("REPAIR anchor hit dialog=" + dialogId + " msg=" + foundId);
                sendRepair(account, userId, mc.getInputPeer(dialogId), foundId, null);
                return;
            }
            if (System.currentTimeMillis() > scanDeadline && scanDeadline != 0L) {
                clearLastOffset(dialogId);
                report("REPAIR deadline hit dialog=" + dialogId);
                if (onMiss != null) {
                    onMiss.run();
                }
                return;
            }
            if (page + 1 < maxPages && oldestId != 0) {
                setLastOffset(dialogId, oldestId);
                Utilities.globalQueue.postRunnable(
                        () -> scanPage(account, userId, dialogId, page + 1, maxPages, onMiss), 250L);
            } else {
                clearLastOffset(dialogId);
                if (onMiss != null) {
                    onMiss.run();
                }
            }
        });
    }

    private static final java.util.HashMap<Long, Integer> lastOffset = new java.util.HashMap<>();

    private static int lastOffsetOf(long dialogId) {
        Integer v = lastOffset.get(dialogId);
        return v == null ? 0 : v;
    }

    private static void setLastOffset(long dialogId, int offset) {
        lastOffset.put(dialogId, offset);
    }

    private static void clearLastOffset(long dialogId) {
        lastOffset.remove(dialogId);
    }

    /** 其余群：每批 6 个并发。 */
    private static void scanRest(final int account, final long userId, final java.util.ArrayList<Long> dialogs,
                                 final Runnable giveUp) {
        if (dialogs.isEmpty()) {
            report("REPAIR anchor-not-found id=" + userId);
            giveUp.run();
            return;
        }
        final int batchSize = 6;
        final int[] index = {0};
        final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable[] nextBatch = new Runnable[1];
        nextBatch[0] = () -> {
            if (settled.get()) {
                return;
            }
            if (index[0] >= dialogs.size()) {
                report("REPAIR anchor-not-found id=" + userId);
                giveUp.run();
                return;
            }
            final int from = index[0];
            final int to = Math.min(dialogs.size(), from + batchSize);
            index[0] = to;
            final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(to - from);
            for (int i = from; i < to; i++) {
                final long dialogId = dialogs.get(i);
                scanPage(account, userId, dialogId, 0, 1, () -> {
                    if (pending.decrementAndGet() == 0 && !settled.get()) {
                        Utilities.globalQueue.postRunnable(nextBatch[0], 200L);
                    }
                });
            }
        };
        nextBatch[0].run();
    }

    private static void report(String s) {
        try {
            org.telegram.ui.Components.MangoActivitiesHook.report(s);
        } catch (Throwable ignore) {
        }
    }

    /** 已经有确切锚点（来源聊天的 dialogId + 他的一条消息 id）时，直接修，不用扫。 */
    public static void repairWithAnchor(final int account, final long userId,
                                        final long dialogId, final int msgId) {
        try {
            MessagesController mc = MessagesController.getInstance(account);
            sendRepair(account, userId, mc.getInputPeer(dialogId), msgId, null);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void sendRepair(final int account, final long peerUserId,
                                   TLRPC.InputPeer peer, int msgId, final Runnable onFail) {
        if (peer == null) {
            if (onFail != null) {
                onFail.run();
            }
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
        TLRPC.TL_inputUserFromMessage from = new TLRPC.TL_inputUserFromMessage();
        from.peer = peer;
        from.msg_id = msgId;
        from.user_id = peerUserId;
        req.id = from;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (!(res instanceof TLRPC.TL_users_userFull)) {
                String why = err instanceof TLRPC.TL_error
                        ? (((TLRPC.TL_error) err).code + " " + ((TLRPC.TL_error) err).text)
                        : String.valueOf(res);
                report("REPAIR fail id=" + peerUserId + " peer=" + peer.getClass().getSimpleName()
                        + " msg=" + msgId + " err=" + why);
                if (onFail != null) {
                    onFail.run();
                }
                return;
            }
            final TLRPC.TL_users_userFull r = (TLRPC.TL_users_userFull) res;
            final TLRPC.UserFull uf = r.full_user;
            if (uf == null) {
                if (onFail != null) {
                    onFail.run();
                }
                return;
            }
            try {
                mc.putUsers(r.users, false);
                mc.putChats(r.chats, false);
                uf.user = mc.getUser(peerUserId);
            } catch (Throwable ignore) {
            }
            // 关键：把完整 User 落到本地 SQLite，这样重启后 getUsers 直接拿到
            // 「min=false + 有效 hash」的用户，资料页走原生路径就能开，不用再扫一遍
            try {
                ArrayList<TLRPC.User> persist = new ArrayList<>(r.users);
                if (uf.user != null) {
                    persist.add(uf.user);
                }
                MessagesStorage.getInstance(account).putUsersAndChats(persist, null, false, true);
                report("REPAIR persisted " + persist.size() + " users id=" + peerUserId);
            } catch (Throwable t) {
                report("REPAIR persist fail " + t);
            }
            try {
                java.lang.reflect.Field f = MessagesController.class.getDeclaredField("fullUsers");
                f.setAccessible(true);
                Object arr = f.get(mc);
                if (arr instanceof androidx.collection.LongSparseArray) {
                    //noinspection unchecked
                    ((androidx.collection.LongSparseArray) arr).put(peerUserId, uf);
                } else if (arr instanceof android.util.LongSparseArray) {
                    //noinspection unchecked
                    ((android.util.LongSparseArray) arr).put(peerUserId, uf);
                }
            } catch (Throwable ignore) {
            }
            synchronized (repairing) {
                repairing.remove(peerUserId);
                repaired.add(peerUserId);
            }
            try {
                org.telegram.ui.Components.MangoActivitiesHook.hint("资料已补全");
            } catch (Throwable ignore) {
            }
            report("REPAIR ok id=" + peerUserId + " uname=" + (uf.user != null ? uf.user.username : "?"));
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.userInfoDidLoad, peerUserId, uf);
                } catch (Throwable ignore) {
                }
            });
        });
    }

    /** 日期 → 群 → 消息 三级分组，全部保序。 */
    public static LinkedHashMap<String, LinkedHashMap<Long, ArrayList<Item>>> group(ArrayList<Item> items) {
        LinkedHashMap<String, LinkedHashMap<Long, ArrayList<Item>>> tree = new LinkedHashMap<>();
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            cal.setTimeInMillis(it.date * 1000L);
            final String day = String.format(Locale.getDefault(), "%02d.%02d.%04d",
                    cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR));
            LinkedHashMap<Long, ArrayList<Item>> byGroup = tree.get(day);
            if (byGroup == null) {
                byGroup = new LinkedHashMap<>();
                tree.put(day, byGroup);
            }
            ArrayList<Item> arr = byGroup.get(it.dialogId);
            if (arr == null) {
                arr = new ArrayList<>();
                byGroup.put(it.dialogId, arr);
            }
            arr.add(it);
        }
        return tree;
    }
}
