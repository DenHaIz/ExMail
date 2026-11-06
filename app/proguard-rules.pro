# EWS-клиент работает через javax.xml DOM и строки — рефлексии нет.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Понятные стеки ошибок в отчётах пользователей: сокращение и оптимизация остаются, имена — нет.
-dontobfuscate
