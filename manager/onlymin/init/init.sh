#!/bin/sh
set -e

MC_ALIAS="local"
MINIO_URL="http://localhost:9000"
BUCKET_NAME="mybucket"
ACCESS_KEY="minio"
SECRET_KEY="minio123"
APP_USER="appuser"
APP_PASS="appuser123"
APP_POLICY_FILE="/init/app-policy.json"
MYBUCKET_POLICY_FILE="/init/mybucket-policy.json"

# Start MinIO server in the background
minio server /data --console-address ":9001" &

# Wait for MinIO readiness
echo "🔁 Waiting for MinIO to become ready..."
until curl -s $MINIO_URL/minio/health/ready > /dev/null; do
  echo "⏳ MinIO not ready, retrying..."
  sleep 1
done
echo "✅ MinIO is ready."

# Configure alias for root credentials
mc alias set $MC_ALIAS $MINIO_URL $ACCESS_KEY $SECRET_KEY

# --- App user & policy ---
echo "👤 Creating app user and policy..."
mc admin policy create $MC_ALIAS app-policy $APP_POLICY_FILE || echo "ℹ️ Policy already exists."
mc admin user add $MC_ALIAS $APP_USER $APP_PASS || echo "ℹ️ User already exists."
mc admin policy attach $MC_ALIAS app-policy --user $APP_USER
echo "✅ App user '$APP_USER' configured for automatic bucket creation."

# --- Demo bucket ---
echo "🪣 Ensuring demo bucket '$BUCKET_NAME' exists..."
if ! mc ls $MC_ALIAS/$BUCKET_NAME > /dev/null 2>&1; then
  mc mb $MC_ALIAS/$BUCKET_NAME
  echo "🪣 Bucket '$BUCKET_NAME' created."
else
  echo "ℹ️ Bucket '$BUCKET_NAME' already exists."
fi

# --- Set anonymous access ---
echo "🌍 Applying anonymous (public read/write) policy for '$BUCKET_NAME'..."
mc anonymous set public $MC_ALIAS/$BUCKET_NAME

echo "🎉 Setup complete!"
echo "------------------------------------------------"
echo "🔑 App user credentials:"
echo "   Access Key: $APP_USER"
echo "   Secret Key: $APP_PASS"
echo ""
echo "🌍 Public bucket:"
echo "   http://localhost:9000/$BUCKET_NAME"
echo "------------------------------------------------"

# Keep container alive
wait
