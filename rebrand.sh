#!/bin/bash
# 把「芒果」品牌和联系方式换成你自己的
# 用法：
#   ./rebrand.sh "小郑客户端" "your_support_username" "your_dev_username" [--dry]
#     $1 = App 名字（桌面图标下面显示的那个，建议带「客户端」）
#     $2 = 客服用户名（不带 @，点了会打开 https://t.me/<这个>）
#     $3 = 开发者用户名（不带 @）
#   加 --dry 只打印改动，不写文件
set -euo pipefail
APP_NAME="${1:?用法: ./rebrand.sh \"App名字\" 客服用户名 开发者用户名 [--dry]}"
SUPPORT="${2:?缺少客服用户名}"
DEV="${3:?缺少开发者用户名}"
DRY="${4:-}"

python3 - "$APP_NAME" "$SUPPORT" "$DEV" "$DRY" <<'PY'
import io, os, sys, re, glob

app, support, dev, dry = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
dry = dry == '--dry'
changed = []

def rewrite(path, fn):
    src = io.open(path, encoding='utf-8').read()
    out = fn(src)
    if out != src:
        changed.append(path)
        if not dry:
            io.open(path, 'w', encoding='utf-8').write(out)

# 1) 所有语言包里的 AppName / AppNameBeta
for f in glob.glob('TMessagesProj/src/main/res/values*/strings.xml'):
    rewrite(f, lambda s: re.sub(r'(<string name="AppName(Beta)?">)(.*?)(</string>)',
                                lambda m: m.group(1) + app + m.group(4), s))

# 2) 中文里出现的「芒果…」文案（各种 mango_*.xml / v*.xml）
for f in glob.glob('TMessagesProj/src/main/res/values*/mango_*.xml') + \
         glob.glob('TMessagesProj/src/main/res/values*/v4*.xml') + \
         glob.glob('TMessagesProj/src/main/res/values/strings.xml'):
    rewrite(f, lambda s: s.replace('芒果客户端', app).replace('芒果版Telegram', app)
                          .replace('芒果性能监控', app + '性能监控')
                          .replace('芒果设置', app + '设置')
                          .replace('芒果行为', app + '行为'))

# 3) Java 里的品牌字面量
java_hits = [
    'TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/MangoResidentService.java',
    'TMessagesProj/src/main/java/org/telegram/ui/MangoSettingsSearchIndex.java',
]
for f in java_hits:
    if os.path.exists(f):
        rewrite(f, lambda s: s.replace('"芒果客户端"', '"%s"' % app)
                              .replace('"芒果版Telegram"', '"%s"' % app)
                              .replace('"让芒果在后台活下来"', '"让%s在后台活下来"' % app)
                              .replace('芒果客户端', app))

# 4) 联系方式：客服 + 开发者
rewrite('TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java',
        lambda s: s.replace('openByUserName("ZSQ888"', 'openByUserName("%s"' % support))
rewrite('TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java',
        lambda s: s.replace('openByUserName("ZSQ888"', 'openByUserName("%s"' % support)
                            .replace('"https://t.me/ZSQ888"', '"https://t.me/%s"' % dev))

print('=== 会被改动的文件 (%d) ===' % len(changed))
for c in sorted(set(changed)):
    print('  ', c)
PY
