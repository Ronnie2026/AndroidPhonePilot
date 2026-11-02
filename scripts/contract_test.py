"""Integration contract test. Requires jsonschema, adb debug app, 8765/8766 forwards.
Select the disposable emulator with ANDROID_SERIAL when multiple devices are connected.
Does not run Root/system write actions. Uses real requests and an independent JSON Schema validator.
"""
import runpy,json,socket,os,struct,urllib.request,urllib.error
from pathlib import Path
from jsonschema import Draft202012Validator
state=runpy.run_path(str(Path(__file__).with_name('smoke_test.py')))
token=state['token'];post=state['post'];base=state['base'];connect=state['connect'];exact=state['exact']
def get(path,auth=True):
 req=urllib.request.Request(base+path,headers={'Authorization':'Bearer '+token} if auth else {})
 try:
  with urllib.request.urlopen(req,timeout=20) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:return e.code,e.read().decode()
def mcp(method,params=None,version='2025-06-18'):
 req=urllib.request.Request(base+'/mcp',json.dumps({'jsonrpc':'2.0','id':10,'method':method,**({'params':params} if params is not None else {})}).encode(),{'Authorization':'Bearer '+token,'Content-Type':'application/json','MCP-Protocol-Version':version})
 with urllib.request.urlopen(req,timeout=20) as r:return json.load(r)
origin_request=urllib.request.Request(base+'/mcp',json.dumps({'jsonrpc':'2.0','id':1,'method':'ping'}).encode(),{'Authorization':'Bearer '+token,'Content-Type':'application/json','Origin':'https://untrusted.example'})
try:urllib.request.urlopen(origin_request);raise AssertionError('untrusted Origin accepted')
except urllib.error.HTTPError as e:assert e.code==403
assert get('/api/actions',False)[0]==401
status,catalog=get('/api/actions');assert status==200 and catalog['schemaVersion']=='1.0'
actions={a['name']:a for a in catalog['actions']}
assert {'list_actions','describe_action','get_status','root_shell','volume_set'}<=actions.keys()
for action in actions.values():
 for field,example in [('inputSchema','exampleArguments'),('outputSchema','exampleResult')]:
  Draft202012Validator.check_schema(action[field]);Draft202012Validator(action[field]).validate(action[example])
 assert action['description'] and action['notes'] and action['exampleIsLive'] is False
 assert get('/api/actions/'+action['name'])[1]==action
assert get('/api/actions/does_not_exist')[0]==404
assert get('/mcp')[0]==405
assert post('/api/actions',{})[0]==405
assert mcp('initialize',{'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{'name':'contract-test','version':'1'}})['result']['protocolVersion']=='2025-06-18'
assert mcp('initialize',{'protocolVersion':'2025-03-26'})['result']['protocolVersion']=='2025-03-26'
modern={t['name']:t for t in mcp('tools/list')['result']['tools']}
legacy={t['name']:t for t in mcp('tools/list',version='2025-03-26')['result']['tools']}
assert modern.keys()==legacy.keys()==actions.keys()
for name in actions:
 assert modern[name]['inputSchema']==actions[name]['inputSchema']==legacy[name]['inputSchema']
 assert modern[name]['outputSchema']==actions[name]['outputSchema']
 assert 'outputSchema' not in legacy[name]
result=mcp('tools/call',{'name':'get_status','arguments':{}})['result']
assert not result['isError'];Draft202012Validator(actions['get_status']['outputSchema']).validate(result['structuredContent'])
assert json.loads(result['content'][0]['text'])==result['structuredContent']
for name,args in [('volume_set',{'value':'50'}),('volume_set',{'value':101}),('volume_set',{'value':1.5}),('volume_set',{'value':-1}),('open_app',{}),('open_app',{'package':'bad;id'}),('root_shell',{'command':''}),('get_status',{'unexpected':True}),('describe_action',{})]:
 http=post('/api/command',{'id':1,'action':name,'args':args})[1]
 assert http['errorCode']=='INVALID_ARGUMENT',(name,http)
 tool=mcp('tools/call',{'name':name,'arguments':args})['result']
 assert tool['isError'] and json.loads(tool['content'][0]['text'])['errorCode']=='INVALID_ARGUMENT'
assert post('/api/command',{'action':'not_implemented'})[1]['errorCode']=='UNKNOWN_ACTION'
assert mcp('tools/call',{'name':'get_status','arguments':[]})['result']['isError']
assert post('/api/command',{'action':'get_status','args':[]})[1]['errorCode']=='INVALID_ARGUMENT'
# Length-aware WebSocket frames also exercise the large dynamic catalog response.
def ws_call(name,args):
 with connect(True) as sock:
  payload=json.dumps({'id':'contract','action':name,'args':args}).encode();mask=os.urandom(4);size=len(payload)
  header=bytes([0x81,(0x80|size) if size<126 else 0xFE])+(struct.pack('!H',size) if size>=126 else b'')
  sock.sendall(header+mask+bytes(c^mask[i%4] for i,c in enumerate(payload)))
  head=exact(sock,2);assert head[0]&15==1;size=head[1]&127
  if size==126:size=struct.unpack('!H',exact(sock,2))[0]
  elif size==127:size=struct.unpack('!Q',exact(sock,8))[0]
  return json.loads(exact(sock,size))
assert ws_call('list_actions',{})['result']==catalog
assert ws_call('describe_action',{'name':'get_status'})['result']==actions['get_status']
assert ws_call('volume_set',{'value':'50'})['errorCode']=='INVALID_ARGUMENT'
assert post('/api/command',{'action':'list_actions','args':{}})[1]['result']==catalog
assert mcp('tools/call',{'name':'describe_action','arguments':{'name':'get_status'}})['result']['structuredContent']==actions['get_status']
contract=get('/api/contract')[1]
assert {a['name'] for a in contract['actions']}==actions.keys() and token not in json.dumps(contract)
for action in contract['actions']:
 for protocol,examples in action['transports'].items():
  request=examples['request'];business=request['params']['arguments'] if protocol=='mcp' else request['args']
  assert business==actions[action['name']]['exampleArguments']
  assert examples['commandExample'] and 'responseExample' in examples
# Exercise GET/POST malformed input behavior without triggering any device write command.
try:
 urllib.request.urlopen(urllib.request.Request(base+'/api/command',b'{broken',{'Authorization':'Bearer '+token,'Content-Type':'application/json'}))
 raise AssertionError('malformed JSON accepted')
except urllib.error.HTTPError as e:assert e.code==400
print(f'PASS: {len(actions)} registered contracts; independent JSON Schema validation; authenticated dynamic discovery; modern/legacy MCP; HTTP/MCP/WS consistency; invalid parameters; Origin rejection; metadata/documentation examples contain no credentials.')
# Optional export of the runtime-generated public documentation, never the secret.
if os.environ.get('PHONEPILOT_EXPORT_DIR'):
 out=Path(os.environ['PHONEPILOT_EXPORT_DIR']);out.mkdir(parents=True,exist_ok=True)
 (out/'AndroidPhonePilot-API-contract.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2))
 for protocol in contract['protocols']:
  pieces=[protocol['guide']]
  for action in contract['actions']:
   examples=action['transports'][protocol['name']]
   pieces.append(f"## {action['title']} · {action['name']}\n\n{action['description']}\n\n{action['notes']}\n\n"+ '\n\n'.join('### '+title+'\n```json\n'+json.dumps(value,ensure_ascii=False,indent=2)+'\n```' for title,value in [('Input Schema',action['inputSchema']),('Output Schema',action['outputSchema']),('Request',examples['request']),('Response example',examples['responseExample'])])+'\n\n```sh\n'+examples['commandExample']+'\n```')
  (out/f"AndroidPhonePilot-{protocol['name'].upper()}-接入说明.md").write_text('\n\n'.join(pieces))
