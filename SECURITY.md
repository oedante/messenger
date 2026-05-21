# 🔒 Безопасность Messenger

## Обзор архитектуры безопасности

```
┌─────────────────────────────────────────────────────────┐
│  Android Device                                          │
│                                                          │
│  ┌──────────────┐    ┌─────────────────────────────┐    │
│  │ Android      │    │ E2EManager                  │    │
│  │ Keystore     │◄──►│ X25519 ECDH                 │    │
│  │ (TEE/SE)     │    │ + AES-256-GCM               │    │
│  └──────────────┘    │ + HKDF (RFC 5869)           │    │
│                      └────────────┬────────────────┘    │
└───────────────────────────────────┼────────────────────-┘
                           TLS 1.3  │  шифротекст
                        ┌───────────▼────────────┐
                        │  nginx (SSL termination)│
                        │  + rate limiting        │
                        │  + security headers     │
                        └───────────┬────────────┘
                                    │ localhost
                        ┌───────────▼────────────┐
                        │  FastAPI Server         │
                        │  Видит только:          │
                        │  • зашифрованный текст  │
                        │  • bcrypt хеши паролей  │
                        └───────────┬────────────┘
                                    │
                        ┌───────────▼────────────┐
                        │  SQLite (WAL mode)      │
                        │  + audit_log            │
                        └────────────────────────┘
```

---

## Шифрование сообщений (E2E)

### Схема (личные чаты)

**Алгоритмы:** X25519 (ECDH) + HKDF-SHA256 + AES-256-GCM

1. **Регистрация:** клиент генерирует пару X25519, приватный ключ — в Android Keystore (шифруется TEE), публичный ключ загружается на сервер.

2. **Отправка сообщения Alice → Bob:**
   ```
   ephemeral_pair     = X25519.generate()
   shared_secret      = X25519(ephemeral_private, bob_public_key)
   aes_key            = HKDF(shared_secret, salt="messenger-e2e-v1", len=32)
   iv                 = random(12 bytes)
   ciphertext         = AES-256-GCM(aes_key, iv, plaintext)
   packet             = base64(ephemeral_pub || iv || ciphertext)
   ```

3. **Расшифровка на устройстве Bob:**
   ```
   ephemeral_pub, iv, ciphertext = parse(packet)
   shared_secret = X25519(bob_private_key, ephemeral_pub)
   aes_key       = HKDF(shared_secret, salt="messenger-e2e-v1", len=32)
   plaintext     = AES-256-GCM.decrypt(aes_key, iv, ciphertext)
   ```

**Сервер видит только base64 шифротекст.** Даже при компрометации БД сообщения нечитаемы.

### Хранение ключей на Android
- Приватный ключ шифруется через **Android Keystore** (AES-256-GCM)
- Хранится в **EncryptedSharedPreferences** — файл шифруется ключом из Keystore
- На устройствах с TEE/Secure Element ключ не покидает аппаратный модуль

### Ограничения текущей E2E реализации
- Группы и каналы — **без E2E** (требует групповых ключей, например MLS/Signal Protocol)
- При смене устройства старые сообщения не расшифруются (нет key backup)
- Нет защиты от компрометации устройства (если root/malware)

---

## Безопасность сервера

### Аутентификация
| Улучшение | Было | Стало |
|-----------|------|-------|
| Хеш паролей | SHA-256 без соли | **bcrypt** (work factor 12, автосоль) |
| Токены | 32 байта | **48 байт** (384 бит энтропии) |
| Срок жизни сессии | Бессрочные | **30 дней** (настраивается) |
| Максимум сессий | Не ограничено | **10 на пользователя** |
| Logout | Удаление токена | + audit log |

### Rate Limiting (slowapi)
| Эндпоинт | Лимит |
|----------|-------|
| /auth/register | 5 запросов / минута / IP |
| /auth/login | 10 запросов / минута / IP |
| /rooms (создание) | 10 запросов / минута |
| Отправка сообщений | 60 запросов / минута |
| Загрузка файлов | 20 запросов / минута |
| WebRTC сигналы | 120 запросов / минута |

### HTTP Security Headers
```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 1; mode=block
Referrer-Policy: strict-origin-when-cross-origin
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'
Strict-Transport-Security: max-age=31536000 (только PROD)
```

### Защита файлов
- **Path traversal:** `os.path.realpath()` + проверка, что путь внутри `uploads/`
- **MIME-тип:** проверка по magic bytes (не по расширению)
- **Белый список MIME:** только разрешённые форматы
- **Рандомные имена:** `token_hex(8)` вместо оригинального имени
- **Размер:** ограничение MAX_FILE_MB (по умолчанию 20 МБ)

### Audit Log
Все чувствительные действия записываются в таблицу `audit_log`:
- register, login, login_fail
- logout, revoke_all_sessions
- create_room, join_room

### Валидация входных данных
```python
username:  3–32 символа, только [a-zA-Z0-9_.-]
password:  8–128 символов
content:   1–65536 символов
file_url:  макс. 128 символов
emoji:     1–8 символов
```

---

## Безопасность Android

### Хранение данных
| Данные | Хранилище | Шифрование |
|--------|-----------|------------|
| Auth token | EncryptedSharedPreferences | AES-256-GCM (Keystore) |
| Приватный ключ E2E | EncryptedSharedPreferences | AES-256-GCM (Keystore) |
| Сообщения | Только в памяти | — |

### Сетевая безопасность
- **network_security_config.xml:** cleartext запрещён в release
- **Certificate Pinning:** настраивается в `build.gradle` через `CERT_PIN`
- **TLS:** только TLS 1.2+ (Android 26+ по умолчанию)

### Защита кода
- **ProGuard/R8:** обфускация в release-сборках
- **Удаление Log.* в release** через ProGuard `assumenosideeffects`
- **android:debuggable="false"** в Manifest

### Проверки среды выполнения (SecurityManager)
- Root detection (su binary, Magisk, тест-ключи)
- Emulator detection
- Signature verification (настраивается)

---

## Чеклист для продакшена

### Обязательно
- [ ] Установить `SECRET_KEY` — минимум 64 случайных байта
- [ ] Включить HTTPS (nginx + Let's Encrypt)
- [ ] Установить `PRODUCTION=1`
- [ ] В `build.gradle` заменить `SERVER_URL` на `https://`
- [ ] Получить certificate pin и указать в `CERT_PIN`
- [ ] Отключить cleartext: `usesCleartextTraffic="false"` (уже выставлено)

### Рекомендуется
- [ ] Настроить TURN-сервер для WebRTC (иначе звонки работают только в локальной сети)
- [ ] Перейти с SQLite на PostgreSQL при нагрузке > 1000 пользователей
- [ ] Настроить автоматические бэкапы `messenger.db`
- [ ] Настроить `ufw` или `iptables` — закрыть порт 8000 снаружи
- [ ] Добавить мониторинг `messenger.log` на аномальные паттерны
- [ ] Для групп реализовать MLS (RFC 9420) или Signal Double Ratchet

### Необязательно, но хорошо
- [ ] Fail2ban на `/auth/login` в nginx логах
- [ ] Ротация логов (`logrotate`)
- [ ] OCSP Stapling в nginx
- [ ] Настроить `HPKP` (если certificate pinning через nginx)
