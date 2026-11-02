# Debug-build integration test. Requires an installed, running app and adb port forwards.
import subprocess,json,xml.etree.ElementTree as ET,urllib.request,urllib.error,socket,base64,os,struct
import shutil
adb=shutil.which("adb")
if not adb: raise SystemExit("Add Android platform-tools to PATH first")
prefs=subprocess.check_output([adb,'shell','run-as','dev.phonepilot','cat','shared_prefs/pilot.xml'])
root=ET.fromstring(prefs)
values=root.find("string[@name='tokens']")
if values is not None:
 import time
 tokens=json.loads(values.text)
 selected=root.find("string[@name='selectedToken']")
 valid=[t for t in tokens if t['enabled'] and (t['expires']==0 or t['expires']>time.time()*1000)]
 if not valid: raise SystemExit("No active Token: create one in app settings")
 token=next((t['secret'] for t in valid if selected is not None and t['id']==selected.text),valid[0]['secret'])
else:
 token=root.find("string[@name='token']").text
base='http://127.0.0.1:8766'
def post(path,data,auth=True):
 r=urllib.request.Request(base+path,json.dumps(data).encode(),{'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if auth else {})})
 try:
  with urllib.request.urlopen(r,timeout=15) as reply:return reply.status,json.load(reply)
 except urllib.error.HTTPError as e:return e.code,e.read().decode()
assert post('/api/command',{'action':'get_status'},False)[0]==401
assert post('/mcp',{'id':1,'method':'ping'},False)[0]==401
status,result=post('/api/command',{'id':1,'action':'get_status'})
assert status==200 and result['result']['httpRunning'] and result['result']['webSocketRunning']
assert 'addresses' in result['result'] and 'batteryPercent' in result['result']
_,mcp=post('/mcp',{'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':'get_status','arguments':{}}})
assert json.loads(mcp['result']['content'][0]['text'])['model']==result['result']['model']
assert 'error' in post('/api/command',{'action':'volume_set','args':{'value':101}})[1]
assert 'error' in post('/api/command',{'action':'open_app','args':{'package':'bad;id'}})[1]
_,tools=post('/mcp',{'jsonrpc':'2.0','id':2,'method':'tools/list'})
assert {'get_status','root_shell','list_actions','describe_action'} <= {t['name'] for t in tools['result']['tools']}
# Minimal RFC6455 client: exercise the real WebSocket server without new dependencies.
def connect(auth):
 s=socket.create_connection(('127.0.0.1',8765));s.settimeout(5)
 key=base64.b64encode(os.urandom(16)).decode()
 request=f'GET /ws HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n'+(f'Authorization: Bearer {token}\r\n' if auth else '')+'\r\n'
 s.sendall(request.encode());buf=b''
 while b'\r\n\r\n' not in buf:buf+=s.recv(1)
 return s
s=connect(True);payload=json.dumps({'id':7,'action':'get_status'}).encode();mask=os.urandom(4)
s.sendall(bytes([0x81,0x80|len(payload)])+mask+bytes(c^mask[i%4] for i,c in enumerate(payload)))
def exact(s,n):
 out=b''
 while len(out)<n:out+=s.recv(n-len(out))
 return out
h=exact(s,2);n=h[1]&127
if n==126:n=struct.unpack('!H',exact(s,2))[0]
if n==127:n=struct.unpack('!Q',exact(s,8))[0]
ws=json.loads(exact(s,n));assert ws['id']==7 and ws['result']['model']==result['result']['model'];s.close()
s=connect(False);assert exact(s,2)[0]&15==8;s.close()
print('PASS: HTTP/MCP unauthorized rejected; HTTP + MCP + WebSocket get_status; dynamic MCP tools; input validation; unauthenticated WebSocket closed.')
