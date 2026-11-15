package org.telegram.ui.Components;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MangoActivityLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 「活动」页。
 *
 * 用 Telegram 自己的 RecyclerListView 承载，而不是 ScrollView：
 * RecyclerListView 是 NestedScrollingChild，能和 ProfileActivity$NestedFrameLayout
 * 走同一套嵌套滚动协议，手势、fling、资料头折叠全都跟原生列表一致。
 */
public class MangoActivitiesView extends FrameLayout {

    public interface OnMessageClickListener {
        void onMessageClick(long dialogId, int msgId);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DAY = 1;
    private static final int TYPE_MESSAGE = 2;

    private static final class Row {
        final int type;
        final String day;
        final MangoActivityLoader.Item item;
        /** 群内第一行：显示头像 + 群名 */
        boolean firstInGroup;
        /** 同一个日期块的第一行：上面两个圆角 */
        boolean firstInDay;
        /** 同一个日期块的最后一行：下面两个圆角 + 与下一块的间距 */
        boolean lastInDay;
        /** 块内最后一行：不画分割线 */
        boolean lastRow;

        Row(int type, String day, MangoActivityLoader.Item item) {
            this.type = type;
            this.day = day;
            this.item = item;
        }
    }

    private final int account;
    private final RecyclerListView listView;
    private final TextView stateView;
    private final ActivitiesAdapter adapter;
    private final ArrayList<Row> rows = new ArrayList<>();

    private RefreshIconView refreshIcon;
    private ObjectAnimator refreshAnim;
    private long peerUserId;
    private OnMessageClickListener listener;
    private boolean requested;
    private boolean loading;
    private long lastUpdate;
    private long requestStamp;

    public MangoActivitiesView(Context context, int account) {
        super(context);
        this.account = account;
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, 1, false));
        listView.setClipToPadding(false);
        listView.setPadding(dp(12), dp(8), dp(12), dp(24));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ActivitiesAdapter());
        addView(listView, new FrameLayout.LayoutParams(MATCH, MATCH));

        stateView = new TextView(context);
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        stateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        stateView.setGravity(Gravity.CENTER);
        stateView.setPadding(dp(24), dp(60), dp(24), dp(24));
        addView(stateView, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP));
        stateView.setVisibility(GONE);
    }

    public RecyclerListView getListView() {
        return listView;
    }

    public void setOnMessageClickListener(OnMessageClickListener l) {
        listener = l;
    }

    public void setPeer(long peerUserId) {
        if (this.peerUserId == peerUserId && requested) {
            return;
        }
        this.peerUserId = peerUserId;
        this.requested = false;
        this.lastUpdate = 0;
        rows.clear();
        // 跟 Nicegram 一样：一进页面先把「最后更新 + 刷新」这张卡摆出来，
        // 转圈由刷新图标负责，不再写"正在读取..."这种字
        rows.add(new Row(TYPE_HEADER, null, null));
        adapter.notifyDataSetChanged();
        stateView.setVisibility(GONE);
        load(false);
    }

    public void reload() {
        load(true);
    }

    private void load(boolean force) {
        if (peerUserId == 0) {
            showState("共同群组里没有找到消息");
            return;
        }
        if (MangoActivityLoader.isFlooded(account)) {
            long minutes = Math.max(1L, MangoActivityLoader.floodRemainingMs(account) / 60000L);
            showState("被 Telegram 限流了，约 " + minutes + " 分钟后再试\n（触发原因是短时间内搜索请求太多，图片/动图会一起卡）");
            stopRefreshAnim();
            return;
        }
        requested = true;
        // 直接命中缓存就不转圈（否则会一直转下去）；真要发网络请求才转
        boolean hitCache = !force && MangoActivityLoader.cached(peerUserId, account) != null;
        loading = !hitCache;
        if (loading) {
            startRefreshAnim();
        }

        final long stamp = ++requestStamp;
        MangoActivityLoader.load(account, peerUserId, force, (acc, peer, items, fromCache) -> {
            if (stamp != requestStamp) {
                return;
            }
            if (!fromCache) {
                lastUpdate = System.currentTimeMillis();
                loading = false;
                stopRefreshAnim();
            }
            render(items);
        });
    }

    private void showState(String text) {
        stateView.setText(text);
        stateView.setVisibility(VISIBLE);
        stopRefreshAnim();
    }

    private void startRefreshAnim() {
        if (refreshIcon == null) {
            return;
        }
        if (refreshAnim == null) {
            refreshAnim = ObjectAnimator.ofFloat(refreshIcon, View.ROTATION, 0f, 360f);
            refreshAnim.setDuration(1000L);
            refreshAnim.setRepeatCount(ValueAnimator.INFINITE);
            refreshAnim.setInterpolator(new LinearInterpolator());
        }
        if (!refreshAnim.isStarted()) {
            refreshAnim.start();
        }
    }

    private void stopRefreshAnim() {
        if (refreshAnim != null && refreshAnim.isStarted()) {
            refreshAnim.cancel();
        }
    }

    // --------------------------------------------------------------- 数据

    private void render(ArrayList<MangoActivityLoader.Item> items) {
        if (!loading) {
            stopRefreshAnim();
        }
        rows.clear();
        rows.add(new Row(TYPE_HEADER, null, null));
        if (items == null || items.isEmpty()) {
            adapter.notifyDataSetChanged();
            showState("共同群组里没有找到消息");
            return;
        }
        stateView.setVisibility(GONE);

        LinkedHashMap<String, LinkedHashMap<Long, ArrayList<MangoActivityLoader.Item>>> tree =
                MangoActivityLoader.group(items);
        for (Map.Entry<String, LinkedHashMap<Long, ArrayList<MangoActivityLoader.Item>>> dayEntry : tree.entrySet()) {
            rows.add(new Row(TYPE_DAY, dayEntry.getKey(), null));
            // 一个日期下所有群合成一张卡片：块首圆上角、块尾圆下角，中间行直角贴在一起
            final int blockStart = rows.size();
            for (Map.Entry<Long, ArrayList<MangoActivityLoader.Item>> groupEntry : dayEntry.getValue().entrySet()) {
                ArrayList<MangoActivityLoader.Item> msgs = groupEntry.getValue();
                for (int i = 0; i < msgs.size(); i++) {
                    Row row = new Row(TYPE_MESSAGE, null, msgs.get(i));
                    row.firstInGroup = i == 0;
                    rows.add(row);
                }
            }
            final int blockEnd = rows.size() - 1;
            if (blockEnd >= blockStart) {
                rows.get(blockStart).firstInDay = true;
                rows.get(blockEnd).lastInDay = true;
                for (int i = blockStart; i <= blockEnd; i++) {
                    rows.get(i).lastRow = i == blockEnd;
                }
            }
        }
        adapter.notifyDataSetChanged();
        listView.scrollToPosition(0);
    }

    // ------------------------------------------------------------- adapter

    private class ActivitiesAdapter extends RecyclerView.Adapter {

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            switch (viewType) {
                case TYPE_DAY:
                    return new SimpleHolder(buildDayHeaderView());
                case TYPE_MESSAGE:
                    return new SimpleHolder(buildMessageCell());
                case TYPE_HEADER:
                default:
                    return new SimpleHolder(buildHeaderCard());
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            switch (row.type) {
                case TYPE_HEADER:
                    bindHeader((LinearLayout) holder.itemView);
                    break;
                case TYPE_DAY:
                    ((TextView) holder.itemView).setText(row.day);
                    break;
                case TYPE_MESSAGE:
                    bindMessage((LinearLayout) holder.itemView, row);
                    break;
            }
        }
    }

    private static class SimpleHolder extends RecyclerView.ViewHolder {
        SimpleHolder(View itemView) {
            super(itemView);
        }
    }

    // --------------------------------------------------------------- 视图

    private TextView buildDayHeaderView() {
        TextView view = text("", 14, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), true);
        view.setLayoutParams(rowParams());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        view.setLayoutParams(lp);
        view.setPadding(dp(4), dp(14), dp(4), dp(8));
        return view;
    }

    private LinearLayout buildHeaderCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(12), dp(10));
        card.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnLp = new LinearLayout.LayoutParams(0, WRAP);
        columnLp.weight = 1f;
        row.addView(column, columnLp);

        TextView title = text("最后更新", 13, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false);
        column.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView value = text("—", 15, Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4), false);
        value.setTag("value");
        column.addView(value, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 竖分割线
        View divider = new View(getContext());
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(26));
        dividerLp.rightMargin = dp(12);
        row.addView(divider, dividerLp);

        // 圆形箭头
        refreshIcon = new RefreshIconView(getContext(),
                Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        refreshIcon.setLayoutParams(iconLp);
        refreshIcon.setOnClickListener(v -> reload());
        row.addView(refreshIcon, iconLp);

        RecyclerView.LayoutParams cardLp = rowParams();
        cardLp.bottomMargin = dp(2);
        card.setLayoutParams(cardLp);
        return card;
    }

    private void bindHeader(LinearLayout card) {
        LinearLayout row = (LinearLayout) card.getChildAt(0);
        LinearLayout column = (LinearLayout) row.getChildAt(0);
        TextView value = (TextView) column.getChildAt(1);
        value.setText(formatLastUpdate());
        // 头部卡片是 RecyclerView 按需创建的，第一次进场时 startRefreshAnim 可能还没拿到图标，
        // 所以在绑定的时候按 loading 状态补一次
        if (loading) {
            startRefreshAnim();
        } else {
            stopRefreshAnim();
        }
    }

    private LinearLayout buildMessageCell() {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setLayoutParams(rowParams());

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        cell.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));

        BackupImageView avatar = new BackupImageView(getContext());
        avatar.setRoundRadius(dp(11));
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        avatarLp.topMargin = dp(2);
        row.addView(avatar, avatarLp);

        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnLp = new LinearLayout.LayoutParams(0, WRAP);
        columnLp.weight = 1f;
        columnLp.leftMargin = dp(9);
        columnLp.rightMargin = dp(9);
        row.addView(column, columnLp);

        TextView name = text("", 17, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(name, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView body = text("", 15, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView time = text("", 12, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), false);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        timeLp.topMargin = dp(2);
        row.addView(time, timeLp);

        View divider = new View(getContext());
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        // 原版分割线是从卡片左内边距开始、到右内边距结束，跟没有头像的行文字对齐
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(1)));
        dividerLp.leftMargin = dp(13);
        dividerLp.rightMargin = dp(13);
        divider.setLayoutParams(dividerLp);
        cell.addView(divider, dividerLp);

        row.setOnClickListener(v -> {
            Object tag = cell.getTag();
            if (tag instanceof MangoActivityLoader.Item && listener != null) {
                MangoActivityLoader.Item it = (MangoActivityLoader.Item) tag;
                listener.onMessageClick(it.dialogId, it.msgId);
            }
        });
        return cell;
    }

    private void bindMessage(LinearLayout cell, Row r) {
        cell.setTag(r.item);
        LinearLayout row = (LinearLayout) cell.getChildAt(0);
        View divider = cell.getChildAt(1);

        BackupImageView avatar = (BackupImageView) row.getChildAt(0);
        LinearLayout column = (LinearLayout) row.getChildAt(1);
        TextView name = (TextView) column.getChildAt(0);
        TextView body = (TextView) column.getChildAt(1);
        TextView time = (TextView) row.getChildAt(2);

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(Long.valueOf(-r.item.dialogId));
        if (chat != null) {
            avatarDrawable.setInfo(account, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
        } else {
            avatarDrawable.setInfo(r.item.dialogId,
                    TextUtils.isEmpty(r.item.groupName) ? "?" : r.item.groupName, "");
            avatar.setImageDrawable(avatarDrawable);
        }

        // 头像和群名只在每个群的第一行出现，后续行不缩进（跟原版一致）
        avatar.setVisibility(r.firstInGroup ? VISIBLE : GONE);
        LinearLayout.LayoutParams columnLp = (LinearLayout.LayoutParams) column.getLayoutParams();
        int indent = r.firstInGroup ? dp(9) : 0;
        if (columnLp.leftMargin != indent) {
            columnLp.leftMargin = indent;
            column.setLayoutParams(columnLp);
        }
        name.setText(TextUtils.isEmpty(r.item.groupName) ? "" : r.item.groupName);
        name.setVisibility(r.firstInGroup ? VISIBLE : GONE);
        body.setText(r.item.text);
        time.setText(formatTime(r.item.date));
        divider.setVisibility(r.lastRow ? GONE : VISIBLE);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        float radius = dp(12);
        float tl = r.firstInDay ? radius : 0;
        float tr = r.firstInDay ? radius : 0;
        float br = r.lastInDay ? radius : 0;
        float bl = r.lastInDay ? radius : 0;
        bg.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        cell.setBackground(bg);

        RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) cell.getLayoutParams();
        int bottom = r.lastInDay ? dp(14) : 0;
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom;
            cell.setLayoutParams(lp);
        }
    }

    private String formatLastUpdate() {
        if (lastUpdate == 0) {
            return "—";
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(lastUpdate);
        return String.format(Locale.getDefault(), "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    private static String formatTime(long date) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(date * 1000L);
        return String.format(Locale.getDefault(), "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        }
        return view;
    }

    /** 「最后更新」右侧那个圆形箭头，自己画，免得依赖 R.drawable（release 里 R 被内联没了）。 */
    private static class RefreshIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final Path arrow = new Path();
        private final int color;

        RefreshIconView(Context context, int color) {
            super(context);
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            final float stroke = Math.max(1.5f, AndroidUtilities.dp(2f));
            final float cx = w / 2f;
            final float cy = h / 2f;
            final float r = Math.min(w, h) / 2f - stroke * 1.7f;

            paint.setColor(color);
            paint.setStrokeWidth(stroke);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            // 留 70° 缺口给箭头
            canvas.drawArc(oval, -62f, 292f, false, paint);

            final float ang = (float) Math.toRadians(-62f);
            final float ax = cx + r * (float) Math.cos(ang);
            final float ay = cy + r * (float) Math.sin(ang);
            final float s = stroke * 2.0f;
            paint.setStyle(Paint.Style.FILL);
            arrow.reset();
            arrow.moveTo(ax + s * 0.55f, ay - s);
            arrow.lineTo(ax - s * 0.75f, ay + s * 0.15f);
            arrow.lineTo(ax + s * 0.15f, ay + s * 0.75f);
            arrow.close();
            canvas.drawPath(arrow, paint);
        }
    }

    /** RecyclerView 的默认 LayoutParams 是 wrap_content，卡片会缩成内容宽度 —— 必须手动占满一行。 */
    private RecyclerView.LayoutParams rowParams() {
        return new RecyclerView.LayoutParams(MATCH, WRAP);
    }

    private int dp(int value) {
        return AndroidUtilities.dp(value);
    }
}
