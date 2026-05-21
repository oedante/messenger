#!/usr/bin/env python3
"""
Messenger Server v3 — FastAPI + WebSocket + WebRTC + E2E Encryption
Исправления безопасности:
  - bcrypt для паролей (вместо SHA-256)
  - Rate limiting (slowapi)
  - Истечение сессий (30 дней)
  - Валидация входных данных (длина, тип)
  - Security headers (X-Frame-Options, CSP, HSTS, etc.)
  - CORS с белым списком
  - Защита от path traversal
  - Проверка MIME-типов файлов
  - Структурированное логирование
  - /docs отключён в продакшене
  - Ограничение числа активных сессий на пользователя
  - Очистка старых сессий
"""

import hashlib, json, logging, mimetypes, os, re, secrets, sqlite3, time
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Set

import bcrypt
import uvicorn
from fastapi import Depends, FastAPI, HTTPException, Query, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, field_validator, Field
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.util import get_remote_address

# ── Конфигурация ───────────────────────────────────────────────────────────────
DB_PATH      = "messenger.db"
UPLOAD_DIR   = "uploads"
SECRET_KEY   = os.getenv("SECRET_KEY", "CHANGE_ME_IN_PRODUCTION_USE_64_RANDOM_BYTES")
MAX_FILE     = int(os.getenv("MAX_FILE_MB", "20")) * 1024 * 1024
PROD         = os.getenv("PRODUCTION", "0") == "1"
SESSION_DAYS = int(os.getenv("SESSION_DAYS", "30"))
MAX_SESSIONS_PER_USER = 10
ALLOWED_ORIGINS = os.getenv("ALLOWED_ORIGINS", "").split(",") or ["*"]

# Разрешённые MIME-типы для загрузки
ALLOWED_MIME = {
    "image/jpeg", "image/png", "image/webp", "image/gif",
    "audio/mpeg", "audio/ogg", "audio/mp4", "audio/webm",
    "video/mp4", "video/webm",
    "application/pdf",
    "text/plain",
    "application/zip",
    "application/octet-stream",
}

os.makedirs(UPLOAD_DIR, exist_ok=True)

# ── Логирование ───────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("messenger.log"),
    ]
)
log = logging.getLogger("messenger")

# ── Rate Limiter ───────────────────────────────────────────────────────────────
limiter = Limiter(key_func=get_remote_address)

# ── БД ────────────────────────────────────────────────────────────────────────
def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn

def init_db():
    conn = get_db()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS users (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            username     TEXT UNIQUE NOT NULL,
            password     TEXT NOT NULL,
            display_name TEXT,
            avatar       TEXT,
            bio          TEXT DEFAULT '',
            online       INTEGER DEFAULT 0,
            last_seen    INTEGER DEFAULT (strftime('%s','now')),
            created_at   INTEGER DEFAULT (strftime('%s','now')),
            -- E2E: открытый ключ X25519 (base64) для обмена ключами
            public_key   TEXT
        );
        CREATE TABLE IF NOT EXISTS sessions (
            token      TEXT PRIMARY KEY,
            user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            device_name TEXT,
            ip_addr    TEXT,
            created_at INTEGER DEFAULT (strftime('%s','now')),
            expires_at INTEGER NOT NULL,
            last_used  INTEGER DEFAULT (strftime('%s','now'))
        );
        CREATE TABLE IF NOT EXISTS rooms (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            type        TEXT NOT NULL CHECK(type IN ('direct','group','channel')),
            title       TEXT, description TEXT DEFAULT '',
            avatar      TEXT, invite_link TEXT UNIQUE,
            created_by  INTEGER REFERENCES users(id),
            created_at  INTEGER DEFAULT (strftime('%s','now'))
        );
        CREATE TABLE IF NOT EXISTS room_members (
            room_id  INTEGER REFERENCES rooms(id) ON DELETE CASCADE,
            user_id  INTEGER REFERENCES users(id) ON DELETE CASCADE,
            role     TEXT DEFAULT 'member' CHECK(role IN ('owner','admin','member')),
            notify   INTEGER DEFAULT 1,
            joined_at INTEGER DEFAULT (strftime('%s','now')),
            PRIMARY KEY (room_id, user_id)
        );
        CREATE TABLE IF NOT EXISTS messages (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            room_id        INTEGER NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
            sender_id      INTEGER REFERENCES users(id),
            -- content хранится в зашифрованном виде (AES-256-GCM, base64)
            content        TEXT NOT NULL,
            type           TEXT DEFAULT 'text' CHECK(type IN ('text','image','file','audio','video','system')),
            file_url       TEXT, file_name TEXT, file_size INTEGER,
            reply_to       INTEGER REFERENCES messages(id),
            forwarded_from INTEGER REFERENCES messages(id),
            edited         INTEGER DEFAULT 0,
            deleted        INTEGER DEFAULT 0,
            pin            INTEGER DEFAULT 0,
            created_at     INTEGER DEFAULT (strftime('%s','now'))
        );
        CREATE TABLE IF NOT EXISTS reactions (
            message_id INTEGER REFERENCES messages(id) ON DELETE CASCADE,
            user_id    INTEGER REFERENCES users(id) ON DELETE CASCADE,
            emoji      TEXT NOT NULL,
            PRIMARY KEY (message_id, user_id)
        );
        CREATE TABLE IF NOT EXISTS read_receipts (
            message_id INTEGER REFERENCES messages(id) ON DELETE CASCADE,
            user_id    INTEGER REFERENCES users(id) ON DELETE CASCADE,
            read_at    INTEGER DEFAULT (strftime('%s','now')),
            PRIMARY KEY (message_id, user_id)
        );
        CREATE TABLE IF NOT EXISTS calls (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            room_id      INTEGER REFERENCES rooms(id),
            initiated_by INTEGER REFERENCES users(id),
            type         TEXT DEFAULT 'audio' CHECK(type IN ('audio','video')),
            status       TEXT DEFAULT 'ringing' CHECK(status IN ('ringing','active','ended','missed')),
            started_at   INTEGER, ended_at INTEGER,
            created_at   INTEGER DEFAULT (strftime('%s','now'))
        );
        CREATE TABLE IF NOT EXISTS call_participants (
            call_id   INTEGER REFERENCES calls(id) ON DELETE CASCADE,
            user_id   INTEGER REFERENCES users(id),
            joined_at INTEGER DEFAULT (strftime('%s','now')),
            left_at   INTEGER,
            PRIMARY KEY (call_id, user_id)
        );
        -- Аудит лог
        CREATE TABLE IF NOT EXISTS audit_log (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id    INTEGER,
            action     TEXT NOT NULL,
            ip_addr    TEXT,
            detail     TEXT,
            created_at INTEGER DEFAULT (strftime('%s','now'))
        );
        CREATE INDEX IF NOT EXISTS idx_messages_room ON messages(room_id, created_at);
        CREATE INDEX IF NOT EXISTS idx_members_user  ON room_members(user_id);
        CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
        CREATE INDEX IF NOT EXISTS idx_sessions_exp  ON sessions(expires_at);
    """)
    conn.commit(); conn.close()

# ── Crypto ────────────────────────────────────────────────────────────────────
def hash_pwd(pwd: str) -> str:
    """bcrypt с авто-солью, work factor 12"""
    return bcrypt.hashpw(pwd.encode(), bcrypt.gensalt(rounds=12)).decode()

def check_pwd(pwd: str, hashed: str) -> bool:
    return bcrypt.checkpw(pwd.encode(), hashed.encode())

def gen_token() -> str:
    return secrets.token_urlsafe(48)  # 384 бит энтропии

def gen_invite() -> str:
    return secrets.token_urlsafe(12)

def safe_filename(filename: str) -> str:
    """Убирает path traversal и опасные символы"""
    name = os.path.basename(filename)
    name = re.sub(r"[^\w.\-]", "_", name)
    return name[:128]  # ограничиваем длину

# ── Auth ──────────────────────────────────────────────────────────────────────
security = HTTPBearer()

def get_current_user(request: Request, credentials: HTTPAuthorizationCredentials = Depends(security)):
    token = credentials.credentials
    now   = int(time.time())
    conn  = get_db()
    row   = conn.execute(
        "SELECT u.*, s.expires_at FROM sessions s JOIN users u ON u.id=s.user_id "
        "WHERE s.token=? AND s.expires_at > ?",
        (token, now)
    ).fetchone()
    if not row:
        conn.close()
        log.warning("Invalid/expired token from %s", request.client.host if request.client else "unknown")
        raise HTTPException(401, "Invalid or expired token")
    # Обновляем last_used
    conn.execute("UPDATE sessions SET last_used=? WHERE token=?", (now, token))
    conn.commit(); conn.close()
    return dict(row)

def audit(conn, user_id: Optional[int], action: str, ip: str, detail: str = ""):
    conn.execute(
        "INSERT INTO audit_log (user_id, action, ip_addr, detail) VALUES (?,?,?,?)",
        (user_id, action, ip, detail[:500])
    )

def _assert_member(conn, room_id: int, user_id: int):
    r = conn.execute("SELECT 1 FROM room_members WHERE room_id=? AND user_id=?", (room_id, user_id)).fetchone()
    if not r: conn.close(); raise HTTPException(403, "Not a member")

def _assert_role(conn, room_id: int, user_id: int, roles: tuple):
    r = conn.execute("SELECT role FROM room_members WHERE room_id=? AND user_id=?", (room_id, user_id)).fetchone()
    if not r or r["role"] not in roles: conn.close(); raise HTTPException(403, "Insufficient permissions")

# ── WebSocket Manager ─────────────────────────────────────────────────────────
class Manager:
    def __init__(self):
        self.sockets: Dict[int, Set[WebSocket]] = {}

    async def connect(self, ws, user_id):
        await ws.accept()
        self.sockets.setdefault(user_id, set()).add(ws)
        conn = get_db()
        conn.execute("UPDATE users SET online=1, last_seen=? WHERE id=?", (int(time.time()), user_id))
        conn.commit(); conn.close()
        await self._broadcast_status(user_id, True)

    async def disconnect(self, ws, user_id):
        self.sockets.get(user_id, set()).discard(ws)
        if not self.sockets.get(user_id):
            conn = get_db()
            conn.execute("UPDATE users SET online=0, last_seen=? WHERE id=?", (int(time.time()), user_id))
            conn.commit(); conn.close()
            await self._broadcast_status(user_id, False)

    async def send(self, user_id, payload):
        dead = set()
        for ws in self.sockets.get(user_id, set()):
            try: await ws.send_json(payload)
            except: dead.add(ws)
        self.sockets.get(user_id, set()).difference_update(dead)

    async def broadcast_room(self, room_id, payload, exclude=None):
        conn = get_db()
        members = conn.execute("SELECT user_id FROM room_members WHERE room_id=?", (room_id,)).fetchall()
        conn.close()
        for m in members:
            if m["user_id"] != exclude:
                await self.send(m["user_id"], payload)

    async def _broadcast_status(self, user_id, online):
        conn = get_db()
        rooms = conn.execute("SELECT DISTINCT room_id FROM room_members WHERE user_id=?", (user_id,)).fetchall()
        conn.close()
        payload = {"type": "user_status", "user_id": user_id, "online": online}
        for r in rooms: await self.broadcast_room(r["room_id"], payload)

mgr = Manager()

# ── Pydantic схемы с валидацией ───────────────────────────────────────────────
class AuthReq(BaseModel):
    username:     str = Field(..., min_length=3,  max_length=32,  pattern=r"^[a-zA-Z0-9_\.\-]+$")
    password:     str = Field(..., min_length=8,  max_length=128)
    display_name: Optional[str] = Field(None, max_length=64)
    device_name:  Optional[str] = Field(None, max_length=64)

class CreateRoomReq(BaseModel):
    type:        str  = Field(..., pattern="^(direct|group|channel)$")
    title:       Optional[str] = Field(None, max_length=128)
    description: Optional[str] = Field(None, max_length=512)
    member_ids:  List[int]     = Field(default=[], max_length=500)

class SendMsgReq(BaseModel):
    content:        str  = Field(..., min_length=1, max_length=65536)
    type:           str  = Field(default="text", pattern="^(text|image|file|audio|video|system)$")
    reply_to:       Optional[int] = None
    forwarded_from: Optional[int] = None

class EditMsgReq(BaseModel):
    content: str = Field(..., min_length=1, max_length=65536)

class ReactionReq(BaseModel):
    emoji: str = Field(..., min_length=1, max_length=8)

class UpdateProfileReq(BaseModel):
    display_name: Optional[str] = Field(None, max_length=64)
    bio:          Optional[str] = Field(None, max_length=512)

class PublicKeyReq(BaseModel):
    public_key: str = Field(..., min_length=40, max_length=64)  # base64 X25519

class InitCallReq(BaseModel):
    room_id: int
    type:    str = Field(default="audio", pattern="^(audio|video)$")

class CallSignalReq(BaseModel):
    call_id:        int
    target_user_id: int
    signal_type:    str = Field(..., pattern="^(offer|answer|ice-candidate|end)$")
    payload:        str = Field(..., max_length=65536)

# ── App lifecycle ─────────────────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app):
    init_db()
    log.info("DB ready — Server starting")
    # Фоновая очистка истёкших сессий при старте
    conn = get_db()
    deleted = conn.execute("DELETE FROM sessions WHERE expires_at < ?", (int(time.time()),)).rowcount
    conn.commit(); conn.close()
    if deleted: log.info("Cleaned %d expired sessions", deleted)
    yield
    log.info("Server shutting down")

# ── Создание приложения ───────────────────────────────────────────────────────
app = FastAPI(
    title="Messenger API",
    version="3.0.0",
    lifespan=lifespan,
    # В продакшене отключаем /docs и /redoc
    docs_url=None if PROD else "/docs",
    redoc_url=None if PROD else "/redoc",
    openapi_url=None if PROD else "/openapi.json",
)

app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# ── CORS ──────────────────────────────────────────────────────────────────────
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PATCH", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
)

# ── Security Headers middleware ────────────────────────────────────────────────
@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"]  = "nosniff"
    response.headers["X-Frame-Options"]          = "DENY"
    response.headers["X-XSS-Protection"]         = "1; mode=block"
    response.headers["Referrer-Policy"]           = "strict-origin-when-cross-origin"
    response.headers["Content-Security-Policy"]   = "default-src 'none'; frame-ancestors 'none'"
    if PROD:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return response

# ── Logging middleware ─────────────────────────────────────────────────────────
@app.middleware("http")
async def log_requests(request: Request, call_next):
    start = time.time()
    response = await call_next(request)
    duration = (time.time() - start) * 1000
    ip = request.client.host if request.client else "unknown"
    log.info("%s %s %d %.1fms ip=%s", request.method, request.url.path, response.status_code, duration, ip)
    if response.status_code >= 400:
        log.warning("HTTP %d: %s %s from %s", response.status_code, request.method, request.url.path, ip)
    return response

# ═══════════════════════════════════════════════════════════════════════════════
# AUTH
# ═══════════════════════════════════════════════════════════════════════════════
@app.post("/auth/register", status_code=201)
@limiter.limit("5/minute")
async def register(request: Request, req: AuthReq):
    conn = get_db()
    try:
        conn.execute(
            "INSERT INTO users (username, password, display_name) VALUES (?,?,?)",
            (req.username.lower(), hash_pwd(req.password), req.display_name or req.username)
        )
        conn.commit()
        uid = conn.execute("SELECT id FROM users WHERE username=?", (req.username.lower(),)).fetchone()["id"]
        token, expires_at = _create_session(conn, uid, req.device_name, request)
        conn.commit()
        audit(conn, uid, "register", request.client.host if request.client else "")
        conn.commit()
        log.info("New user registered: %s", req.username)
        return {"token": token, "user_id": uid, "expires_at": expires_at}
    except sqlite3.IntegrityError:
        raise HTTPException(409, "Username taken")
    finally:
        conn.close()

@app.post("/auth/login")
@limiter.limit("10/minute")
async def login(request: Request, req: AuthReq):
    conn = get_db()
    user = conn.execute("SELECT * FROM users WHERE username=?", (req.username.lower(),)).fetchone()
    ip = request.client.host if request.client else "unknown"
    if not user or not check_pwd(req.password, user["password"]):
        audit(conn, user["id"] if user else None, "login_fail", ip, req.username)
        conn.commit(); conn.close()
        log.warning("Failed login for username=%s from %s", req.username, ip)
        raise HTTPException(401, "Invalid credentials")

    # Очищаем старые сессии пользователя если их слишком много
    count = conn.execute("SELECT COUNT(*) FROM sessions WHERE user_id=?", (user["id"],)).fetchone()[0]
    if count >= MAX_SESSIONS_PER_USER:
        conn.execute(
            "DELETE FROM sessions WHERE user_id=? AND rowid IN "
            "(SELECT rowid FROM sessions WHERE user_id=? ORDER BY last_used ASC LIMIT ?)",
            (user["id"], user["id"], count - MAX_SESSIONS_PER_USER + 1)
        )

    token, expires_at = _create_session(conn, user["id"], req.device_name, request)
    audit(conn, user["id"], "login", ip)
    conn.commit(); conn.close()
    log.info("User logged in: id=%d from %s", user["id"], ip)
    return {"token": token, "user_id": user["id"], "expires_at": expires_at}

def _create_session(conn, user_id: int, device_name: Optional[str], request: Request):
    token      = gen_token()
    expires_at = int(time.time()) + SESSION_DAYS * 86400
    ip         = request.client.host if request.client else "unknown"
    conn.execute(
        "INSERT INTO sessions (token, user_id, device_name, ip_addr, expires_at) VALUES (?,?,?,?,?)",
        (token, user_id, device_name or "unknown", ip, expires_at)
    )
    return token, expires_at

@app.post("/auth/logout")
async def logout(request: Request, creds=Depends(security), u=Depends(get_current_user)):
    conn = get_db()
    conn.execute("DELETE FROM sessions WHERE token=?", (creds.credentials,))
    audit(conn, u["id"], "logout", request.client.host if request.client else "")
    conn.commit(); conn.close()
    return {"ok": True}

@app.get("/auth/sessions")
async def list_sessions(u=Depends(get_current_user)):
    """Список активных сессий пользователя"""
    conn = get_db()
    rows = conn.execute(
        "SELECT device_name, ip_addr, created_at, last_used, expires_at FROM sessions WHERE user_id=? ORDER BY last_used DESC",
        (u["id"],)
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]

@app.delete("/auth/sessions/all")
async def revoke_all_sessions(request: Request, creds=Depends(security), u=Depends(get_current_user)):
    """Выход со всех устройств"""
    conn = get_db()
    conn.execute("DELETE FROM sessions WHERE user_id=? AND token!=?", (u["id"], creds.credentials))
    audit(conn, u["id"], "revoke_all_sessions", request.client.host if request.client else "")
    conn.commit(); conn.close()
    return {"ok": True}

# ═══════════════════════════════════════════════════════════════════════════════
# USERS
# ═══════════════════════════════════════════════════════════════════════════════
@app.get("/users/me")
async def get_me(u=Depends(get_current_user)):
    return {k: u[k] for k in ("id","username","display_name","avatar","bio","online","last_seen","public_key")}

@app.patch("/users/me")
async def update_profile(req: UpdateProfileReq, u=Depends(get_current_user)):
    conn = get_db()
    if req.display_name is not None:
        conn.execute("UPDATE users SET display_name=? WHERE id=?", (req.display_name, u["id"]))
    if req.bio is not None:
        conn.execute("UPDATE users SET bio=? WHERE id=?", (req.bio, u["id"]))
    conn.commit(); conn.close(); return {"ok": True}

@app.post("/users/me/public_key")
async def upload_public_key(req: PublicKeyReq, u=Depends(get_current_user)):
    """
    Загрузка X25519 публичного ключа для E2E шифрования.
    Клиент генерирует ключевую пару при регистрации,
    приватный ключ хранится только на устройстве.
    """
    conn = get_db()
    conn.execute("UPDATE users SET public_key=? WHERE id=?", (req.public_key, u["id"]))
    conn.commit(); conn.close()
    return {"ok": True}

@app.post("/users/me/avatar")
@limiter.limit("10/minute")
async def upload_avatar(request: Request, file: UploadFile, u=Depends(get_current_user)):
    data = await file.read(MAX_FILE + 1)
    if len(data) > MAX_FILE:
        raise HTTPException(400, f"File too large (max {MAX_FILE // 1024 // 1024} MB)")
    # Проверка MIME по содержимому, а не по расширению
    detected = _detect_mime(data, file.filename or "")
    if not detected.startswith("image/"):
        raise HTTPException(400, "Only image files are allowed for avatar")
    ext  = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp", "image/gif": "gif"}.get(detected, "jpg")
    name = f"av_{u['id']}_{secrets.token_hex(8)}.{ext}"
    with open(f"{UPLOAD_DIR}/{name}", "wb") as f: f.write(data)
    conn = get_db()
    conn.execute("UPDATE users SET avatar=? WHERE id=?", (name, u["id"]))
    conn.commit(); conn.close()
    return {"avatar": name}

@app.get("/users/search")
@limiter.limit("30/minute")
async def search_users(request: Request, q: str = Query(..., min_length=1, max_length=64), u=Depends(get_current_user)):
    conn = get_db()
    rows = conn.execute(
        "SELECT id,username,display_name,avatar,online,public_key FROM users "
        "WHERE (username LIKE ? OR display_name LIKE ?) AND id!=? LIMIT 30",
        (f"%{q}%", f"%{q}%", u["id"])
    ).fetchall()
    conn.close(); return [dict(r) for r in rows]

@app.get("/users/{uid}")
async def get_user(uid: int, _=Depends(get_current_user)):
    conn = get_db()
    r = conn.execute(
        "SELECT id,username,display_name,avatar,bio,online,last_seen,public_key FROM users WHERE id=?", (uid,)
    ).fetchone()
    conn.close()
    if not r: raise HTTPException(404, "Not found")
    return dict(r)

# ═══════════════════════════════════════════════════════════════════════════════
# ROOMS
# ═══════════════════════════════════════════════════════════════════════════════
@app.get("/rooms")
async def list_rooms(u=Depends(get_current_user)):
    conn = get_db()
    rows = conn.execute("""
        SELECT r.id, r.type, r.title, r.description, r.avatar, r.invite_link,
               (SELECT content FROM messages WHERE room_id=r.id ORDER BY created_at DESC LIMIT 1) AS last_msg,
               (SELECT created_at FROM messages WHERE room_id=r.id ORDER BY created_at DESC LIMIT 1) AS last_msg_at,
               (SELECT COUNT(*) FROM messages m WHERE m.room_id=r.id AND m.sender_id != ?
                AND m.deleted=0 AND NOT EXISTS
                (SELECT 1 FROM read_receipts WHERE message_id=m.id AND user_id=?)) AS unread,
               rm.role
        FROM rooms r JOIN room_members rm ON rm.room_id=r.id AND rm.user_id=?
        ORDER BY last_msg_at DESC
    """, (u["id"], u["id"], u["id"])).fetchall()
    result = []
    for r in rows:
        d = dict(r)
        if d["type"] == "direct":
            other = conn.execute("""
                SELECT u2.id,u2.username,u2.display_name,u2.avatar,u2.online,u2.public_key
                FROM room_members rm2 JOIN users u2 ON u2.id=rm2.user_id
                WHERE rm2.room_id=? AND rm2.user_id!=?
            """, (d["id"], u["id"])).fetchone()
            if other:
                d.update({"title": other["display_name"] or other["username"],
                           "avatar": other["avatar"], "other_id": other["id"],
                           "online": bool(other["online"]), "other_public_key": other["public_key"]})
        result.append(d)
    conn.close(); return result

@app.post("/rooms", status_code=201)
@limiter.limit("10/minute")
async def create_room(request: Request, req: CreateRoomReq, u=Depends(get_current_user)):
    conn = get_db()
    if req.type == "direct" and len(req.member_ids) == 1:
        existing = conn.execute("""
            SELECT r.id FROM rooms r
            JOIN room_members a ON a.room_id=r.id AND a.user_id=?
            JOIN room_members b ON b.room_id=r.id AND b.user_id=?
            WHERE r.type='direct'
        """, (u["id"], req.member_ids[0])).fetchone()
        if existing: conn.close(); return {"id": existing["id"], "invite_link": None, "exists": True}
    invite = gen_invite() if req.type in ("group","channel") else None
    conn.execute("INSERT INTO rooms (type,title,description,invite_link,created_by) VALUES (?,?,?,?,?)",
                 (req.type, req.title, req.description or "", invite, u["id"]))
    conn.commit()
    room_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    for uid in list({u["id"]} | set(req.member_ids)):
        role = "owner" if uid == u["id"] else "member"
        conn.execute("INSERT OR IGNORE INTO room_members (room_id,user_id,role) VALUES (?,?,?)", (room_id, uid, role))
    audit(conn, u["id"], "create_room", request.client.host if request.client else "", f"type={req.type}")
    conn.commit(); conn.close()
    return {"id": room_id, "invite_link": invite, "exists": False}

@app.get("/rooms/{room_id}")
async def get_room(room_id: int, u=Depends(get_current_user)):
    conn = get_db(); _assert_member(conn, room_id, u["id"])
    room    = conn.execute("SELECT * FROM rooms WHERE id=?", (room_id,)).fetchone()
    members = conn.execute("""
        SELECT u2.id,u2.username,u2.display_name,u2.avatar,u2.online,u2.public_key,rm.role
        FROM room_members rm JOIN users u2 ON u2.id=rm.user_id WHERE rm.room_id=?
    """, (room_id,)).fetchall()
    pinned  = conn.execute("SELECT * FROM messages WHERE room_id=? AND pin=1 ORDER BY created_at DESC LIMIT 5", (room_id,)).fetchall()
    conn.close()
    return {**dict(room), "members": [dict(m) for m in members], "pinned": [dict(p) for p in pinned]}

@app.post("/rooms/join/{invite_link}")
@limiter.limit("20/minute")
async def join_by_invite(request: Request, invite_link: str, u=Depends(get_current_user)):
    if not re.match(r"^[A-Za-z0-9_\-]{12,20}$", invite_link):
        raise HTTPException(400, "Invalid invite format")
    conn = get_db()
    room = conn.execute("SELECT * FROM rooms WHERE invite_link=?", (invite_link,)).fetchone()
    if not room: raise HTTPException(404, "Invalid invite")
    conn.execute("INSERT OR IGNORE INTO room_members (room_id,user_id) VALUES (?,?)", (room["id"], u["id"]))
    audit(conn, u["id"], "join_room", request.client.host if request.client else "", f"room_id={room['id']}")
    conn.commit(); conn.close()
    await mgr.broadcast_room(room["id"], {"type": "member_joined", "room_id": room["id"], "user_id": u["id"]})
    return {"id": room["id"]}

@app.post("/rooms/{room_id}/members/{uid}")
async def add_member(room_id: int, uid: int, u=Depends(get_current_user)):
    conn = get_db(); _assert_role(conn, room_id, u["id"], ("owner","admin"))
    conn.execute("INSERT OR IGNORE INTO room_members (room_id,user_id) VALUES (?,?)", (room_id, uid))
    conn.commit(); conn.close()
    await mgr.broadcast_room(room_id, {"type": "member_added", "room_id": room_id, "user_id": uid})
    return {"ok": True}

@app.delete("/rooms/{room_id}/members/{uid}")
async def remove_member(room_id: int, uid: int, u=Depends(get_current_user)):
    conn = get_db()
    if uid != u["id"]: _assert_role(conn, room_id, u["id"], ("owner","admin"))
    conn.execute("DELETE FROM room_members WHERE room_id=? AND user_id=?", (room_id, uid))
    conn.commit(); conn.close()
    await mgr.broadcast_room(room_id, {"type": "member_removed", "room_id": room_id, "user_id": uid})
    return {"ok": True}

# ═══════════════════════════════════════════════════════════════════════════════
# MESSAGES
# Важно: content хранится ЗАШИФРОВАННЫМ (AES-256-GCM base64).
# Сервер не видит plaintext — только шифротекст.
# ═══════════════════════════════════════════════════════════════════════════════
@app.get("/rooms/{room_id}/messages")
async def get_messages(room_id: int,
                       before: Optional[int] = None,
                       limit: int = Query(50, ge=1, le=100),
                       u=Depends(get_current_user)):
    conn = get_db(); _assert_member(conn, room_id, u["id"])
    q = """SELECT m.*, u2.username AS sender_name, u2.display_name AS sender_display,
                  u2.avatar AS sender_avatar
           FROM messages m LEFT JOIN users u2 ON u2.id=m.sender_id
           WHERE m.room_id=? AND m.deleted=0"""
    params = [room_id]
    if before:
        q += " AND m.id<?"; params.append(before)
    q += " ORDER BY m.created_at DESC LIMIT ?"; params.append(limit)
    rows = conn.execute(q, params).fetchall()
    for r in rows:
        conn.execute("INSERT OR IGNORE INTO read_receipts (message_id,user_id) VALUES (?,?)", (r["id"], u["id"]))
    conn.commit(); conn.close()
    return [dict(r) for r in reversed(rows)]

@app.post("/rooms/{room_id}/messages", status_code=201)
@limiter.limit("60/minute")
async def send_message(request: Request, room_id: int, req: SendMsgReq, u=Depends(get_current_user)):
    conn = get_db(); _assert_member(conn, room_id, u["id"])
    room = conn.execute("SELECT type FROM rooms WHERE id=?", (room_id,)).fetchone()
    if room and room["type"] == "channel": _assert_role(conn, room_id, u["id"], ("owner","admin"))
    conn.execute(
        "INSERT INTO messages (room_id,sender_id,content,type,reply_to,forwarded_from) VALUES (?,?,?,?,?,?)",
        (room_id, u["id"], req.content, req.type, req.reply_to, req.forwarded_from)
    )
    conn.commit()
    mid = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    msg = conn.execute("""SELECT m.*, u2.username AS sender_name, u2.display_name AS sender_display,
                                 u2.avatar AS sender_avatar
                          FROM messages m LEFT JOIN users u2 ON u2.id=m.sender_id WHERE m.id=?""", (mid,)).fetchone()
    conn.close()
    await mgr.broadcast_room(room_id, {"type": "new_message", "message": dict(msg)})
    return dict(msg)

@app.post("/rooms/{room_id}/messages/upload")
@limiter.limit("20/minute")
async def upload_file_msg(request: Request, room_id: int, file: UploadFile, u=Depends(get_current_user)):
    data = await file.read(MAX_FILE + 1)
    if len(data) > MAX_FILE:
        raise HTTPException(400, f"File too large (max {MAX_FILE // 1024 // 1024} MB)")
    detected = _detect_mime(data, file.filename or "")
    if detected not in ALLOWED_MIME:
        raise HTTPException(400, f"File type not allowed: {detected}")
    safe_name = safe_filename(file.filename or "file")
    ext       = safe_name.rsplit(".", 1)[-1] if "." in safe_name else "bin"
    stored    = f"msg_{u['id']}_{secrets.token_hex(8)}.{ext}"
    with open(f"{UPLOAD_DIR}/{stored}", "wb") as f: f.write(data)
    mtype = "image" if detected.startswith("image/") else ("audio" if detected.startswith("audio/") else "file")
    conn  = get_db(); _assert_member(conn, room_id, u["id"])
    conn.execute(
        "INSERT INTO messages (room_id,sender_id,content,type,file_url,file_name,file_size) VALUES (?,?,?,?,?,?,?)",
        (room_id, u["id"], safe_name, mtype, stored, safe_name, len(data))
    )
    conn.commit()
    mid = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    msg = conn.execute("""SELECT m.*, u2.username AS sender_name, u2.display_name AS sender_display,
                                 u2.avatar AS sender_avatar
                          FROM messages m LEFT JOIN users u2 ON u2.id=m.sender_id WHERE m.id=?""", (mid,)).fetchone()
    conn.close()
    await mgr.broadcast_room(room_id, {"type": "new_message", "message": dict(msg)})
    return dict(msg)

@app.patch("/rooms/{room_id}/messages/{mid}")
@limiter.limit("30/minute")
async def edit_message(request: Request, room_id: int, mid: int, req: EditMsgReq, u=Depends(get_current_user)):
    conn = get_db()
    m = conn.execute("SELECT * FROM messages WHERE id=? AND sender_id=? AND deleted=0", (mid, u["id"])).fetchone()
    if not m: raise HTTPException(404, "Not found or not yours")
    conn.execute("UPDATE messages SET content=?,edited=1 WHERE id=?", (req.content, mid))
    conn.commit(); conn.close()
    await mgr.broadcast_room(room_id, {"type": "message_edited", "message_id": mid, "content": req.content})
    return {"ok": True}

@app.delete("/rooms/{room_id}/messages/{mid}")
async def delete_message(room_id: int, mid: int, u=Depends(get_current_user)):
    conn = get_db()
    m = conn.execute("SELECT * FROM messages WHERE id=? AND deleted=0", (mid,)).fetchone()
    if not m: raise HTTPException(404, "Not found")
    member = conn.execute("SELECT role FROM room_members WHERE room_id=? AND user_id=?", (room_id, u["id"])).fetchone()
    if m["sender_id"] != u["id"] and (not member or member["role"] not in ("owner","admin")):
        raise HTTPException(403, "Forbidden")
    conn.execute("UPDATE messages SET deleted=1 WHERE id=?", (mid,))
    conn.commit(); conn.close()
    await mgr.broadcast_room(room_id, {"type": "message_deleted", "message_id": mid})
    return {"ok": True}

@app.post("/rooms/{room_id}/messages/{mid}/pin")
async def pin_message(room_id: int, mid: int, u=Depends(get_current_user)):
    conn = get_db(); _assert_role(conn, room_id, u["id"], ("owner","admin"))
    conn.execute("UPDATE messages SET pin=1 WHERE id=?", (mid,))
    conn.commit(); conn.close()
    await mgr.broadcast_room(room_id, {"type": "message_pinned", "message_id": mid})
    return {"ok": True}

@app.post("/rooms/{room_id}/messages/{mid}/react")
@limiter.limit("60/minute")
async def react(request: Request, room_id: int, mid: int, req: ReactionReq, u=Depends(get_current_user)):
    conn = get_db(); _assert_member(conn, room_id, u["id"])
    existing = conn.execute("SELECT emoji FROM reactions WHERE message_id=? AND user_id=?", (mid, u["id"])).fetchone()
    if existing and existing["emoji"] == req.emoji:
        conn.execute("DELETE FROM reactions WHERE message_id=? AND user_id=?", (mid, u["id"])); action = "removed"
    else:
        conn.execute("INSERT OR REPLACE INTO reactions (message_id,user_id,emoji) VALUES (?,?,?)", (mid, u["id"], req.emoji)); action = "added"
    conn.commit(); conn.close()
    await mgr.broadcast_room(room_id, {"type": "reaction", "message_id": mid, "user_id": u["id"], "emoji": req.emoji, "action": action})
    return {"ok": True}

# ═══════════════════════════════════════════════════════════════════════════════
# CALLS
# ═══════════════════════════════════════════════════════════════════════════════
@app.post("/calls/init", status_code=201)
@limiter.limit("10/minute")
async def init_call(request: Request, req: InitCallReq, u=Depends(get_current_user)):
    conn = get_db(); _assert_member(conn, req.room_id, u["id"])
    conn.execute("INSERT INTO calls (room_id,initiated_by,type,status) VALUES (?,?,?,'ringing')", (req.room_id, u["id"], req.type))
    conn.commit()
    call_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    conn.execute("INSERT INTO call_participants (call_id,user_id) VALUES (?,?)", (call_id, u["id"]))
    conn.commit(); conn.close()
    await mgr.broadcast_room(req.room_id, {"type": "call_incoming", "call_id": call_id,
                                            "room_id": req.room_id, "call_type": req.type, "caller_id": u["id"]}, exclude=u["id"])
    return {"call_id": call_id}

@app.post("/calls/{call_id}/accept")
async def accept_call(call_id: int, u=Depends(get_current_user)):
    conn = get_db()
    call = conn.execute("SELECT * FROM calls WHERE id=?", (call_id,)).fetchone()
    if not call: raise HTTPException(404, "Call not found")
    _assert_member(conn, call["room_id"], u["id"])
    conn.execute("UPDATE calls SET status='active', started_at=? WHERE id=?", (int(time.time()), call_id))
    conn.execute("INSERT OR IGNORE INTO call_participants (call_id,user_id) VALUES (?,?)", (call_id, u["id"]))
    conn.commit(); conn.close()
    await mgr.broadcast_room(call["room_id"], {"type": "call_accepted", "call_id": call_id, "user_id": u["id"]})
    return {"ok": True}

@app.post("/calls/{call_id}/end")
async def end_call(call_id: int, u=Depends(get_current_user)):
    conn = get_db()
    call = conn.execute("SELECT * FROM calls WHERE id=?", (call_id,)).fetchone()
    if not call: raise HTTPException(404, "Call not found")
    _assert_member(conn, call["room_id"], u["id"])
    conn.execute("UPDATE calls SET status='ended', ended_at=? WHERE id=?", (int(time.time()), call_id))
    conn.execute("UPDATE call_participants SET left_at=? WHERE call_id=? AND user_id=?", (int(time.time()), call_id, u["id"]))
    conn.commit(); conn.close()
    await mgr.broadcast_room(call["room_id"], {"type": "call_ended", "call_id": call_id, "ended_by": u["id"]})
    return {"ok": True}

@app.post("/calls/signal")
@limiter.limit("120/minute")
async def webrtc_signal(request: Request, req: CallSignalReq, u=Depends(get_current_user)):
    await mgr.send(req.target_user_id, {"type": "webrtc_signal", "call_id": req.call_id,
                                         "from_user_id": u["id"], "signal_type": req.signal_type, "payload": req.payload})
    return {"ok": True}

# ═══════════════════════════════════════════════════════════════════════════════
# FILES — с защитой от path traversal
# ═══════════════════════════════════════════════════════════════════════════════
@app.get("/files/{filename:path}")
async def serve_file(filename: str, _=Depends(get_current_user)):
    # Защита от path traversal
    safe = safe_filename(filename)
    if safe != filename or "/" in filename or "\\" in filename:
        raise HTTPException(400, "Invalid filename")
    abs_path = os.path.realpath(os.path.join(UPLOAD_DIR, safe))
    upload_abs = os.path.realpath(UPLOAD_DIR)
    if not abs_path.startswith(upload_abs + os.sep):
        raise HTTPException(400, "Invalid path")
    if not os.path.isfile(abs_path):
        raise HTTPException(404, "File not found")
    return FileResponse(abs_path)

# ═══════════════════════════════════════════════════════════════════════════════
# WEBSOCKET
# ═══════════════════════════════════════════════════════════════════════════════
@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket, token: str = Query(...)):
    now  = int(time.time())
    conn = get_db()
    sess = conn.execute(
        "SELECT user_id FROM sessions WHERE token=? AND expires_at>?", (token, now)
    ).fetchone()
    conn.execute("UPDATE sessions SET last_used=? WHERE token=?", (now, token))
    conn.commit(); conn.close()
    if not sess:
        await ws.close(code=4001); return
    user_id = sess["user_id"]
    await mgr.connect(ws, user_id)
    try:
        while True:
            try:
                data = await ws.receive_json()
            except Exception:
                break
            t = data.get("type")
            if t == "typing":
                room_id = int(data.get("room_id", 0))
                if room_id > 0:
                    await mgr.broadcast_room(room_id, {"type": "typing", "user_id": user_id, "room_id": room_id}, exclude=user_id)
            elif t == "ping":
                await ws.send_json({"type": "pong"})
            elif t == "read":
                mid = data.get("message_id")
                if mid and isinstance(mid, int):
                    conn2 = get_db()
                    conn2.execute("INSERT OR IGNORE INTO read_receipts (message_id,user_id) VALUES (?,?)", (mid, user_id))
                    conn2.commit(); conn2.close()
    except WebSocketDisconnect:
        pass
    finally:
        await mgr.disconnect(ws, user_id)

# ═══════════════════════════════════════════════════════════════════════════════
# HELPERS
# ═══════════════════════════════════════════════════════════════════════════════
def _detect_mime(data: bytes, filename: str) -> str:
    """Определяет MIME по magic bytes (первые байты файла)"""
    # Простая проверка по magic bytes без внешних зависимостей
    sigs = {
        b"\xff\xd8\xff": "image/jpeg",
        b"\x89PNG\r\n": "image/png",
        b"RIFF": "image/webp",   # + check offset 8 == WEBP
        b"GIF87a": "image/gif",
        b"GIF89a": "image/gif",
        b"ID3": "audio/mpeg",
        b"\xff\xfb": "audio/mpeg",
        b"OggS": "audio/ogg",
        b"ftyp": "video/mp4",   # offset 4
        b"%PDF": "application/pdf",
        b"PK\x03\x04": "application/zip",
    }
    for magic, mime in sigs.items():
        if data[:len(magic)] == magic:
            return mime
        if magic == b"ftyp" and data[4:8] == magic:
            return mime
        if magic == b"RIFF" and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
            return "image/webp"
    # fallback к расширению
    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    fallback = {
        "mp3": "audio/mpeg", "ogg": "audio/ogg", "m4a": "audio/mp4",
        "mp4": "video/mp4",  "webm": "video/webm",
        "txt": "text/plain", "zip": "application/zip", "pdf": "application/pdf",
    }
    return fallback.get(ext, "application/octet-stream")

# ═══════════════════════════════════════════════════════════════════════════════
# ENTRY POINT
# ═══════════════════════════════════════════════════════════════════════════════
if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=int(os.getenv("PORT", "8000")),
        reload=False,
        workers=1,
        # В продакшене используйте nginx + SSL перед этим сервисом
    )
