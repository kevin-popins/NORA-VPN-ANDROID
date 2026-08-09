# Установка NORA VPN

## Android

1. Скачайте APK из раздела Releases.
2. Разрешите установку приложений из выбранного источника.
3. Установите APK и откройте NORA VPN.
4. Добавьте профиль или подписку через кнопку `+`.
5. Выберите сервер и подтвердите системный запрос Android на создание VPN.

Поддерживается Android 8.0 и новее.

## Обновление

Новый официальный APK можно установить поверх предыдущей версии. Профили, подписки и настройки сохранятся.

## Сборка из исходников

Для сборки понадобятся Android Studio, JDK 17 и Android SDK 36.

```powershell
.\gradlew.bat :app:assembleDebug
```

Готовый APK появится в каталоге:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Запуск тестов:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```
