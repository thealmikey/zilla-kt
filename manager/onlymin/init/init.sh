#!/bin/sh
set -e

MC_ALIAS="local"
MINIO_URL="http://localhost:9000"
BUCKET_NAME="mybucket"
ACCESS_KEY="minio"
SECRET_KEY="minio123"
POLICY_FILE="/init/policy.json"

# Start MinIO server in the background
minio server /data --console-address ":9001" &

# Wait for MinIO to become ready
echo "🔁 Waiting for MinIO to become ready..."
until curl -s $MINIO_URL/minio/health/ready > /dev/null; do
  echo "⏳ MinIO not ready, retrying..."
  sleep 1
done

echo "✅ MinIO is ready."

# Configure mc alias
mc alias set $MC_ALIAS $MINIO_URL $ACCESS_KEY $SECRET_KEY

# Create bucket if it doesn't exist
mc mb $MC_ALIAS/$BUCKET_NAME || echo "ℹ️ Bucket already exists."

# Apply custom anonymous policy
mc anonymous set-json $POLICY_FILE $MC_ALIAS/$BUCKET_NAME

echo "🎉 MinIO bucket '$BUCKET_NAME' is initialized with anonymous upload and download access."

# Wait for MinIO process to keep the container running
wait