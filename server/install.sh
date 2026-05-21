#!/usr/bin/env bash
# ============================================================
# Установка Messenger Server v3 (Secure) на Debian 11/12
# Использование: sudo bash install.sh [--with-nginx] [--with-ssl yourdomain.com]
# ============================================================
set -euo pipefail
GREEN="\033[0;32m"; YELLOW="\033[1;33m"; RED="\033[0;31m"; RESET="\033[0m"
info()  { echo -e "${GREEN}[✓]${RESET} $*"; }
warn()  { echo -e "${YELLOW}[!]${RESET} $*"; }
error() { echo -e "${RED}[✗]${RESET} $*"; exit 1; }

WITH_NGINX=0
DOMAIN=""
for arg in "$@"; do
    case $arg in
        --with-nginx) WITH_NGINX=1 ;;
        --with-ssl)   shift; DOMAIN="$1" ;;
    esac
done

echo "=== Messenger Server v3 Secure Installer ==="

# Проверяем root
[ "$EUID" -eq 0 ] || error "Запустите с sudo"

# Зависимости
apt-get update -q
apt-get install -y python3 python3-pip python3-venv libmagic1

info "Python: $(python3 --version)"

# Пользователь
id -u messenger &>/dev/null || useradd --system --no-create-home --shell /bin/false messenger

# Директория
APP=/opt/messenger
mkdir -p $APP/uploads
chmod 750 $APP/uploads

cp main.py      $APP/
cp requirements.txt $APP/

# Виртуальное окружение
python3 -m venv $APP/venv
$APP/venv/bin/pip install --quiet --upgrade pip
$APP/venv/bin/pip install --quiet -r $APP/requirements.txt
info "Python packages installed"

# Генерируем SECRET_KEY
SECRET=$(python3 -c "import secrets; print(secrets.token_hex(64))")

# Права
chown -R messenger:messenger $APP
chmod 700 $APP

# Systemd unit
cat > /etc/systemd/system/messenger.service << EOF
[Unit]
Description=Messenger API Server v3
After=network.target

[Service]
Type=simple
User=messenger
Group=messenger
WorkingDirectory=$APP
ExecStart=$APP/venv/bin/python main.py
Restart=on-failure
RestartSec=5

# Безопасность systemd
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=$APP

# Переменные окружения
Environment=SECRET_KEY=$SECRET
Environment=MAX_FILE_MB=20
Environment=SESSION_DAYS=30
Environment=PRODUCTION=1

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable messenger
systemctl start messenger
info "Service started"

# Firewall
if command -v ufw &>/dev/null; then
    ufw allow 22/tcp comment "SSH"
    ufw allow 80/tcp comment "HTTP"
    ufw allow 443/tcp comment "HTTPS"
    # Закрываем прямой доступ к порту 8000 снаружи
    ufw deny 8000/tcp
    ufw --force enable
    info "UFW firewall configured"
fi

# Nginx
if [ $WITH_NGINX -eq 1 ]; then
    apt-get install -y nginx
    cp nginx.conf /etc/nginx/sites-available/messenger
    ln -sf /etc/nginx/sites-available/messenger /etc/nginx/sites-enabled/
    rm -f /etc/nginx/sites-enabled/default
    nginx -t && systemctl reload nginx
    info "Nginx configured"

    if [ -n "$DOMAIN" ]; then
        apt-get install -y certbot python3-certbot-nginx
        certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m admin@"$DOMAIN"
        info "SSL certificate obtained for $DOMAIN"
    fi
fi

echo ""
echo -e "${GREEN}✅  Установка завершена!${RESET}"
echo "   IP:     http://$(hostname -I | awk '{print $1}'):8000"
[ -n "$DOMAIN" ] && echo "   Домен:  https://$DOMAIN"
echo "   Статус: systemctl status messenger"
echo "   Логи:   journalctl -u messenger -f"
echo ""
warn "SECRET_KEY сохранён в /etc/systemd/system/messenger.service"
warn "Сделайте бэкап файла $APP/messenger.db!"
