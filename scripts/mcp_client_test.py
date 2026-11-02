"""Real official MCP SDK 2.3.0 Streamable HTTP client test, with explicit opt-in for a physical device.
Uses reversible media-volume actions and restores the original level; Root `id` is read-only.
Requires ANDROID_SERIAL, adb forwards, and scripts/requirements-test.txt.
Physical devices additionally require PHONEPILOT_ALLOW_DEVICE=1.
"""
import asyncio,importlib.metadata,json,os,shutil,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
import httpx2
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
serial=os.environ.get('ANDROID_SERIAL','')
if not serial:raise SystemExit('Set ANDROID_SERIAL to the intended device.')
if not serial.startswith('emulator-') and os.environ.get('PHONEPILOT_ALLOW_DEVICE')!='1':raise SystemExit('Physical-device testing changes/restores media volume: set PHONEPILOT_ALLOW_DEVICE=1 explicitly.')
adb=shutil.which('adb')
prefs=ET.fromstring(subprocess.check_output([adb,'-s',serial,'shell','run-as','dev.phonepilot','cat','shared_prefs/pilot.xml']))
values=json.loads(prefs.find("string[@name='tokens']").text)
import time
valid=[v for v in values if v['enabled'] and (v['expires']==0 or v['expires']>time.time()*1000)]
selected=prefs.find("string[@name='selectedToken']")
token=next((v['secret'] for v in valid if selected is not None and v['id']==selected.text),valid[0]['secret'])
http_port=int(os.environ.get('PHONEPILOT_HTTP_PORT','8766'))
report={'sdkVersion':importlib.metadata.version('mcp'),'transport':'Streamable HTTP (stateless JSON responses)','endpoint':f'http://127.0.0.1:{http_port}/mcp','responseFormat':'Official SDK parsed results; may include SDK default fields, not raw HTTP bodies','calls':[]}
def dump(model):return model.model_dump(mode='json',by_alias=True,exclude_none=True)
def business(model):
 value=dump(model)
 if value.get('isError'):raise RuntimeError(json.dumps(value,ensure_ascii=False))
 return value.get('structuredContent') or json.loads(next(v['text'] for v in value['content'] if v['type']=='text'))
async def main():
 async with httpx2.AsyncClient(headers={'Authorization':'Bearer '+token},timeout=20,trust_env=False) as client:
  async with streamable_http_client(report['endpoint'],http_client=client) as (read,write):
   async with ClientSession(read,write,read_timeout_seconds=20) as session:
    initialized=await session.initialize();report['initialize']=dump(initialized)
    listed=await session.list_tools();report['toolNames']=[tool.name for tool in listed.tools]
    assert {'get_status','volume_set','root_shell','list_actions'}<=set(report['toolNames'])
    async def call(name,args=None):
     result=await session.call_tool(name,args or {})
     report['calls'].append({'name':name,'arguments':args or {},'response':dump(result)})
     return result
    before=business(await call('get_status'));level=before['volume'];maximum=before['volumeMax'];assert maximum>0
    report['volumeBefore']=level
    try:
     action='volume_up' if level<maximum else 'volume_down'
     changed=business(await call(action));expected=min(maximum,level+1) if action=='volume_up' else max(0,level-1)
     assert changed['volume']==expected
     checked=business(await call('get_status'));assert checked['volume']==expected
     report['volumeChanged']=checked['volume']
     invalid=dump(await call('volume_set',{'value':101}));assert invalid.get('isError') is True
    finally:
     percent=(level*100+maximum-1)//maximum
     restored=business(await call('volume_set',{'value':percent}));assert restored['volume']==level
     report['volumeRestored']=restored['volume']
    root=await call('root_shell',{'command':'id'});root_json=dump(root)
    if root_json.get('isError'):report['rootAvailable']=False;report['rootResult']=root_json
    else:
     root_data=business(root);report['rootAvailable']=root_data.get('exitCode')==0 and 'uid=0(' in root_data.get('output','');report['rootResult']=root_data
    schema=business(await call('describe_action',{'name':'volume_set'}));assert schema['inputSchema']['properties']['value']['maximum']==100
    final=business(await call('get_status'));assert final['volume']==level
 report['passed']=True
asyncio.run(main())
path=os.environ.get('PHONEPILOT_MCP_REPORT')
if path:Path(path).write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('PASS: official MCP SDK',report['sdkVersion'],'Streamable HTTP client negotiated',report['initialize']['protocolVersion'],'and discovered',len(report['toolNames']),'tools.')
print(f"PASS: actual media volume {report['volumeBefore']} -> {report['volumeChanged']} -> {report['volumeRestored']}; invalid value rejected; dynamic action schema queried.")
print('Root `id` available:',report['rootAvailable'])
print('Root result:',json.dumps(report['rootResult'],ensure_ascii=False))
