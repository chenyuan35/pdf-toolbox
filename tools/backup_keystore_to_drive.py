"""Upload the PDF Toolbox signing key backup to the user's Google Drive.

One-time setup: opens a browser for Google OAuth consent (uses the client
secret originally created for hermes). The refresh token is stored locally in
tools/.drive_token.json so later runs are silent.

Scope is drive.file — the app can only see files it created itself.
"""
import sys
import os
from pathlib import Path

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

SCOPES = ["https://www.googleapis.com/auth/drive.file"]
HERE = Path(__file__).resolve().parent
CLIENT_SECRET = Path(r"C:\Users\59314\Downloads\client_secret_57193976546-8ecg77dgmbt4qbo2o9lurebcgvo27bap.apps.googleusercontent.com.json")
TOKEN_PATH = HERE / ".drive_token.json"
FOLDER_NAME = "PDF Toolbox 签名密钥备份"


def get_credentials():
    creds = None
    if TOKEN_PATH.exists():
        creds = Credentials.from_authorized_user_file(str(TOKEN_PATH), SCOPES)
    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
    if not creds or not creds.valid:
        flow = InstalledAppFlow.from_client_secrets_file(str(CLIENT_SECRET), SCOPES)
        creds = flow.run_local_server(port=0, prompt="consent")
        TOKEN_PATH.write_text(creds.to_json(), encoding="utf-8")
    return creds


def find_or_create_folder(service):
    query = (f"mimeType='application/vnd.google-apps.folder' "
             f"and name='{FOLDER_NAME}' and trashed=false")
    result = service.files().list(q=query, fields="files(id,name)").execute()
    files = result.get("files", [])
    if files:
        return files[0]["id"]
    meta = {"name": FOLDER_NAME, "mimeType": "application/vnd.google-apps.folder"}
    folder = service.files().create(body=meta, fields="id").execute()
    return folder["id"]


def upload(service, folder_id, path: Path):
    meta = {"name": path.name, "parents": [folder_id]}
    media = MediaFileUpload(str(path), resumable=True)
    request = service.files().create(body=meta, media_body=media, fields="id,size,webViewLink")
    response = None
    while response is None:
        status, response = request.next_chunk()
        if status:
            print(f"上传进度: {int(status.progress() * 100)}%", flush=True)
    return response


def main():
    zip_path = HERE.parent / "signing-key-backup.zip"
    if not zip_path.exists():
        print(f"ERROR: backup not found: {zip_path}")
        sys.exit(1)

    creds = get_credentials()
    service = build("drive", "v3", credentials=creds)
    folder_id = find_or_create_folder(service)
    print(f"云端文件夹 ID: {folder_id}")

    result = upload(service, folder_id, zip_path)
    print("上传完成!")
    print(f"文件 ID: {result['id']}")
    print(f"大小: {result.get('size')} bytes")
    print(f"文件夹链接: https://drive.google.com/drive/folders/{folder_id}")


if __name__ == "__main__":
    main()
