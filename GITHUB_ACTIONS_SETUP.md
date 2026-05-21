# 🚀 Сборка APK через GitHub Actions

Пошаговая инструкция — APK будет готов через ~5 минут после пуша.

---

## Шаг 1 — Создайте репозиторий на GitHub

1. Зайдите на [github.com](https://github.com) → **New repository**
2. Название: `messenger` (приватный — **Private**)
3. Нажмите **Create repository**

---

## Шаг 2 — Загрузите проект

```bash
# Распакуйте архив messenger_secure.zip
unzip messenger_secure.zip
cd messenger

# Инициализируйте git
git init
git add .
git commit -m "Initial commit"

# Подключите репозиторий (замените YOUR_USERNAME)
git remote add origin https://github.com/YOUR_USERNAME/messenger.git
git branch -M main
git push -u origin main
```

---

## Шаг 3 — Установите секрет SERVER_URL

1. Откройте репозиторий на GitHub
2. **Settings** → **Secrets and variables** → **Actions**
3. Нажмите **New repository secret**
4. Добавьте:

| Name | Value |
|------|-------|
| `SERVER_URL` | `http://ВАШ_IP_СЕРВЕРА:8000` |

> Для продакшена: `https://ваш-домен.com` (после настройки SSL)

---

## Шаг 4 — Получите Debug APK (без подписи)

После пуша в `main` GitHub Actions запустится автоматически:

1. Откройте вкладку **Actions** в репозитории
2. Нажмите на запуск **Build & Sign APK**
3. Дождитесь зелёной галочки (~5 минут)
4. Прокрутите вниз → **Artifacts** → скачайте **Messenger-Debug-APK**

✅ Это полноценный APK, просто подписан debug-ключом.

---

## Шаг 5 (опционально) — Подписанный Release APK

Для публикации в Google Play или распространения нужна ваша подпись.

### 5.1 Сгенерируйте keystore через GitHub Actions

1. Вкладка **Actions** → **Generate Keystore (запустить один раз)**
2. Нажмите **Run workflow** → заполните поля → **Run workflow**
3. После выполнения: **Actions → запуск → Artifacts → keystore-secrets**
4. Скачайте файл `keystore-secrets.txt` — в нём 4 значения

> ⚠️ Файл удалится через 24 часа. Сохраните пароли в надёжном месте!

### 5.2 Добавьте секреты подписи

**Settings → Secrets → Actions → New repository secret** для каждого:

| Secret Name | Откуда взять |
|-------------|-------------|
| `KEYSTORE_BASE64` | Из `keystore-secrets.txt` |
| `KEYSTORE_PASSWORD` | Из `keystore-secrets.txt` |
| `KEY_ALIAS` | Из `keystore-secrets.txt` |
| `KEY_PASSWORD` | Из `keystore-secrets.txt` |

### 5.3 Запустите сборку заново

Сделайте любой пуш или запустите вручную:
**Actions → Build & Sign APK → Run workflow → release**

Теперь в артефактах появится **Messenger-Release-APK**.

---

## Шаг 6 — Установка APK на телефон

### Вариант A: Через USB
```bash
# Установите adb (Android Debug Bridge)
adb install Messenger-release-*.apk
```

### Вариант B: Вручную
1. Перенесите APK на телефон (USB, облако, Telegram)
2. **Настройки → Безопасность → Разрешить установку из неизвестных источников**
3. Откройте APK-файл → **Установить**

### Вариант C: QR-код (удобно)
Загрузите APK на любой файлообменник (Google Drive, Yandex Disk) и создайте QR-код ссылки на [qr-code-generator.com](https://qr-code-generator.com).

---

## Автоматический релиз при теге

Чтобы APK автоматически создавался в **Releases** при публикации версии:

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions создаст Release с APK автоматически.

---

## Структура файлов GitHub Actions

```
.github/
└── workflows/
    ├── build.yml              ← Основной: сборка + подпись APK
    └── generate_keystore.yml  ← Вспомогательный: генерация keystore
```

---

## Частые проблемы

**❌ Ошибка: `SDK location not found`**
→ В файле `android/local.properties` добавьте:
```
sdk.dir=/usr/local/lib/android/sdk
```
Но обычно это решается автоматически через `android-actions/setup-android@v3`.

**❌ Ошибка: `Execution failed for task ':app:mergeDebugResources'`**
→ Проверьте, что все XML-файлы в `res/` валидны.

**❌ APK установился, но не подключается к серверу**
→ Проверьте секрет `SERVER_URL` — он должен быть доступен с телефона.
→ Если сервер на локальном IP (192.168.x.x), телефон должен быть в той же сети.
