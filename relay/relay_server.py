import flask
import imaplib
import ssl
import os
import json
from flask import Flask, request, Response, jsonify

app = Flask(__name__)

SESSIONS = {}  # session_id -> imaplib.IMAP4_SSL

@app.route('/health')
def health():
    return 'ok'

@app.route('/connect', methods=['POST'])
def connect():
    """Open IMAP connection, return session_id"""
    data = request.get_json()
    host = data.get('host', 'imap.sohu.com')
    try:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        imap = imaplib.IMAP4_SSL(host, 993, ssl_context=ctx, timeout=30)
        greeting = imap.welcome or 'OK'
        import uuid
        sid = uuid.uuid4().hex
        SESSIONS[sid] = imap
        return jsonify({'ok': True, 'session': sid, 'greeting': greeting})
    except Exception as e:
        return jsonify({'ok': False, 'error': str(e)}), 500

@app.route('/cmd', methods=['POST'])
def cmd():
    """Send IMAP command, return response"""
    data = request.get_json()
    sid = data.get('session')
    command = data.get('command')
    imap = SESSIONS.get(sid)
    if not imap:
        return jsonify({'ok': False, 'error': 'session not found'}), 404
    try:
        typ, resp = imap._simple_command(command.split()[0], *command.split()[1:])
        data_list = []
        for r in resp:
            if isinstance(r, bytes):
                data_list.append(r.decode('utf-8', errors='replace'))
            elif isinstance(r, tuple):
                data_list.append(str(r))
            else:
                data_list.append(str(r))
        return jsonify({'ok': typ == 'OK', 'status': typ, 'data': data_list})
    except Exception as e:
        return jsonify({'ok': False, 'error': str(e)}), 500

@app.route('/close', methods=['POST'])
def close():
    data = request.get_json()
    sid = data.get('session')
    imap = SESSIONS.pop(sid, None)
    if imap:
        try: imap.logout()
        except: pass
    return jsonify({'ok': True})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=443, ssl_context=('/home/marvis/relay/cert.pem', '/home/marvis/relay/key.pem'))
