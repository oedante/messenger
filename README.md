# 📱 Messenger — Android + Python FastAPI

Полнофункциональный мессенджер с поддержкой:
- Личные чаты (1-на-1)
- Группы с админами и пригласительными ссылками
- Каналы (только администраторы могут публиковать)
- Аудио и видеозвонки (WebRTC)
- Реакции на сообщения, ответы, пересылка
- Закреплённые сообщения
- Статус онлайн / индикатор «печатает»
- Отправка файлов, изображений, аудио
- Аватары пользователей
- Шифрованное хранение токена на Android

---

## 🖥 Сервер (Python FastAPI) — Debian Linux

### Быстрая установка
```bash
git clone / скопируйте папку server/ на сервер
cd server
sudo bash install.sh
```

Скрипт автоматически:
- Устанавливает Python 3 и зависимости
- Создаёт системного пользователя `messenger`
- Регистрирует и запускает systemd-сервис
- Генерирует случайный SECRET_KEY

### Ручной запуск (для разработки)
```bash
cd server
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python main.py
# Сервер запустится на http://0.0.0.0:8000
```

### Управление сервисом
```bash
systemctl status messenger       # Статус
systemctl restart messenger      # Перезапуск
journalctl -u messenger -f       # Логи в реальном времени
```

### API документация
После запуска откройте: `http://YOUR_SERVER_IP:8000/docs`

### Переменные окружения
| Переменная   | По умолчанию | Описание                    |
|-------------|-------------|-----------------------------|
| SECRET_KEY  | CHANGE_ME   | Ключ хеширования паролей    |
| MAX_FILE_MB | 20          | Максимальный размер файла   |

---

## 📱 Android-приложение (Kotlin)

### Требования
- Android Studio Hedgehog (2023.1.1) или новее
- JDK 17
- Android SDK 26+

### Настройка
1. Откройте папку `android/` в Android Studio
2. В файле `app/build.gradle` замените IP сервера:
```groovy
buildConfigField "String", "SERVER_URL", "\"http://192.168.1.100:8000\""
buildConfigField "String", "WS_URL",     "\"ws://192.168.1.100:8000/ws\""
```
3. Для продакшена используйте HTTPS/WSS и настройте `network_security_config.xml`
4. Нажмите **Run** или `Shift+F10`

### Архитектура приложения
```
ui/
  LoginActivity        — Вход в аккаунт
  RegisterActivity     — Регистрация
  RoomListActivity     — Список чатов/групп/каналов
  NewRoomActivity      — Создание чата, группы, канала
  ProfileActivity      — Профиль пользователя
  chat/
    ChatActivity       — Экран переписки
  call/
    CallActivity       — Аудио/видеозвонок (WebRTC)

network/
  RetrofitClient       — HTTP-клиент
  WsManager            — WebSocket (реалтайм события)
  CallManager          — WebRTC peer connection

data/
  SessionManager       — Зашифрованное хранение токена

models/
  Models.kt            — Все data-классы
```

### WebRTC (звонки)
Используется библиотека `io.github.webrtc-sdk:android`.

Для работы в интернете (не только в локальной сети) нужен **TURN-сервер**.
Добавьте его в `CallManager.kt`:
```kotlin
PeerConnection.IceServer.builder("turn:YOUR_TURN_SERVER:3478")
    .setUsername("user")
    .setPassword("password")
    .createIceServer()
```

Бесплатные варианты TURN: **Coturn** (self-hosted), **Metered.ca** (cloud).

---

## 🔒 Безопасность (для продакшена)

1. **HTTPS**: настройте nginx как reverse proxy с SSL
2. **SECRET_KEY**: используйте длинный случайный ключ (уже генерируется при install.sh)
3. **Firewall**: откройте только порты 80, 443 (nginx) и 22 (SSH)
4. **БД**: для высокой нагрузки замените SQLite на PostgreSQL
5. **Файлы**: используйте S3-совместимое хранилище вместо локальной папки

### Пример nginx конфига
```nginx
server {
    listen 443 ssl;
    server_name your-domain.com;
    
    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }
}
```

---

## 📋 REST API — краткий справочник

| Метод  | URL                              | Описание                    |
|--------|----------------------------------|-----------------------------|
| POST   | /auth/register                   | Регистрация                 |
| POST   | /auth/login                      | Вход                        |
| POST   | /auth/logout                     | Выход                       |
| GET    | /users/me                        | Мой профиль                 |
| GET    | /users/search?q=...              | Поиск пользователей         |
| GET    | /rooms                           | Список чатов                |
| POST   | /rooms                           | Создать чат/группу/канал    |
| GET    | /rooms/{id}/messages             | История сообщений           |
| POST   | /rooms/{id}/messages             | Отправить сообщение         |
| POST   | /rooms/{id}/messages/upload      | Отправить файл              |
| POST   | /rooms/{id}/messages/{id}/react  | Реакция                     |
| POST   | /calls/init                      | Начать звонок               |
| POST   | /calls/{id}/accept               | Принять звонок              |
| POST   | /calls/signal                    | WebRTC сигнал               |
| WS     | /ws?token=...                    | WebSocket соединение        |

Полная документация: `http://YOUR_SERVER:8000/docs`
