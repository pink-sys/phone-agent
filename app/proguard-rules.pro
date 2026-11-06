# 目前没有引入需要混淆的第三方库。
# 如果以后开启了 minify，注意别把无障碍服务与 Activity 的类名裁掉：
-keep class cn.wbphone.agent.PhoneAccessibilityService { *; }
-keep class cn.wbphone.agent.MainActivity { *; }
