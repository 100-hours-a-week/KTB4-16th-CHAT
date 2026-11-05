#!/usr/bin/env python3
"""Owned local MySQL/Redis only: two JVMs, real STOMP, committed DB gaps and shutdown."""
import base64, datetime, glob, json, os, pathlib, shutil, socket, struct, subprocess, threading, time, urllib.request, urllib.error, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
ROOT=pathlib.Path(__file__).resolve().parents[1]
MYSQL='mulo-chat-pubsub-test-mysql'; REDIS='mulo-chat-pubsub-test-redis'
class Stub(BaseHTTPRequestHandler):
    """Only test authentication and moderation; never logs request credentials."""
    def do_POST(self):
        size=int(self.headers.get('Content-Length','0')); body=self.rfile.read(size)
        if self.path=='/internal/auth/verify':
            try:user=int(self.headers.get('Authorization','').split('user-')[1])
            except (ValueError,IndexError):self.send_error(401);return
            value={'userId':user,'expiresAt':(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)).isoformat()}
        else:
            request=json.loads(body);value={'requestId':request['requestId'],'isToxic':False,'confidence':1.0,'category':'SAFE','modelVersion':'test'}
        data=json.dumps(value).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    def log_message(self,*args):pass

def http(port,path,user=17,data=None,internal=False):
    """Exercise actual HTTP authentication and JSON contracts."""
    headers={'Authorization':'Bearer user-'+str(user),'Content-Type':'application/json'}
    if internal:headers['X-Internal-Api-Key']='chat-smoke-internal'
    request=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=None if data is None else json.dumps(data).encode(),headers=headers)
    with urllib.request.urlopen(request,timeout=10) as response:
        if '/messages/sync' in path:assert response.headers.get('Cache-Control')=='no-store'
        return json.loads(response.read() or b'{}')

class Stomp:
    """Minimal RFC6455 test peer; masks client frames and reads actual STOMP messages."""
    def __init__(self,port,user):
        self.socket=socket.create_connection(('127.0.0.1',port),timeout=5);self.socket.settimeout(8)
        key=base64.b64encode(os.urandom(16)).decode()
        self.socket.sendall(('GET /ws HTTP/1.1\r\nHost: localhost\r\nOrigin: http://localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: '+key+'\r\n\r\n').encode())
        header=b''
        while not header.endswith(b'\r\n\r\n'):header+=self.exact(1)
        if b' 101 ' not in header:self.socket.close();raise RuntimeError('WebSocket not ready')
        self.send('CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer user-'+str(user)+'\nheart-beat:0,0\n\n\0')
        assert self.read().startswith('CONNECTED')
        self.send('SUBSCRIBE\nid:messages\ndestination:/user/queue/chat-messages\n\n\0')
        self.send('SUBSCRIBE\nid:events\ndestination:/user/queue/chat-events\n\n\0')
    def exact(self,n):
        value=b''
        while len(value)<n:
            part=self.socket.recv(n-len(value))
            if not part:raise EOFError('Socket closed')
            value+=part
        return value
    def send(self,text,opcode=1):
        data=text.encode() if isinstance(text,str) else text;size=len(data);mask=os.urandom(4)
        header=bytes([0x80|opcode,0x80|(size if size<126 else 126)])+(struct.pack('!H',size) if size>=126 else b'')
        self.socket.sendall(header+mask+bytes(c^mask[i%4] for i,c in enumerate(data)))
    def read(self):
        while True:
            header=self.exact(2);op=header[0]&15;size=header[1]&127
            if size==126:size=struct.unpack('!H',self.exact(2))[0]
            elif size==127:size=struct.unpack('!Q',self.exact(8))[0]
            payload=self.exact(size)
            if op==8:raise EOFError('WebSocket CLOSE '+str(struct.unpack('!H',payload[:2])[0] if len(payload)>=2 else 0))
            if op==9:self.send(payload,10);continue
            if op==1 and payload.strip(b'\n\r\0'):return payload.decode()
    def message(self,client_id):
        end=time.monotonic()+10
        while time.monotonic()<end:
            frame=self.read()
            if not frame.startswith('MESSAGE'):raise AssertionError(frame[:150])
            value=json.loads(frame.split('\n\n',1)[1].rstrip('\0'))
            if value.get('clientMessageId')==client_id:return value
        raise AssertionError('Message not delivered')
    def close(self):self.socket.close()

def connect(port,user):
    """Wait for real subscription readiness, not process health alone."""
    last=None
    for _ in range(30):
        try:return Stomp(port,user)
        except (OSError,RuntimeError,EOFError) as error:last=error;time.sleep(.5)
    raise last

def sql(text):
    """Insert a test-only committed/not-published fixture in the owned DB."""
    return subprocess.check_output(['docker','exec','-i',MYSQL,'mysql','--skip-column-names','-umulo_chat','-pchat-test-password','mulo_chat'],input=text,text=True,stderr=subprocess.DEVNULL)

def main():
    stub=ThreadingHTTPServer(('127.0.0.1',17889),Stub);threading.Thread(target=stub.serve_forever,daemon=True).start()
    jar=next((ROOT/'build/libs').glob('*.jar'));processes=[];logs=[];sockets=[]
    env=dict(os.environ,CHAT_DB_URL='jdbc:mysql://127.0.0.1:3334/mulo_chat?serverTimezone=Asia/Seoul',CHAT_DB_USERNAME='mulo_chat',CHAT_DB_PASSWORD='chat-test-password',CHAT_WS_ALLOWED_ORIGINS='http://localhost',CHAT_BACKEND_URL='http://127.0.0.1:17889',CHAT_INTERNAL_API_KEY='chat-smoke-internal',CHAT_MODERATION_AI_BASE_URL='http://127.0.0.1:17889',AI_INTERNAL_TOKEN='chat-smoke-ai',CHAT_DELIVERY_ENABLED='true',CHAT_REDIS_HOST='127.0.0.1',CHAT_REDIS_PORT='16401',CHAT_DELIVERY_ENVIRONMENT='smoke')
    java=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    try:
        for port in (18981,18982):
            log=open('/tmp/mulo-chat-smoke-'+str(port)+'.log','w');logs.append(log)
            processes.append(subprocess.Popen([java,'-jar',str(jar),'--server.port='+str(port)],env=env,stdout=log,stderr=subprocess.STDOUT))
        for port in (18981,18982):
            for _ in range(80):
                try:
                    with urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/health',timeout=1):break
                except (OSError,urllib.error.HTTPError):time.sleep(.25)
            else:raise RuntimeError('Process health failed '+str(port))
        room=http(18981,'/internal/chat-rooms',data={'matchId':int(time.time()*1000000),'expiresAt':(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=1)).isoformat(),'participants':[{'userId':17,'nickname':'one'},{'userId':18,'nickname':'two'}]},internal=True)['chatRoomId']
        for user in (17,18):http(18981,f'/api/chat-rooms/{room}/members',user,data={})
        a=connect(18981,17);c=connect(18982,18);sockets.extend([a,c]);time.sleep(.3)
        client=str(uuid.uuid4());a.send(f'SEND\ndestination:/app/chat-rooms/{room}/messages\ncontent-type:application/json\n\n'+json.dumps({'clientMessageId':client,'content':'cross-task smoke'})+'\0')
        first=a.message(client);peer=c.message(client);assert first['chatMessageId']==peer['chatMessageId']
        before=first['chatMessageId'];print('PASS cross-JVM STOMP delivery and sender echo',flush=True)
        try:http(18982,f'/api/chat-rooms/{room}/messages/sync',19);raise AssertionError('Unauthorized room read allowed')
        except urllib.error.HTTPError as error:assert error.code in (403,404)
        started=time.monotonic();processes[0].terminate();processes[0].wait(timeout=50);assert time.monotonic()-started<50
        print('PASS SIGTERM shutdown within 50s',flush=True)
        values=','.join(f"({room},17,'{uuid.uuid4()}','gap-{i}')" for i in range(45));sql('INSERT INTO chat_messages(chat_room_id,user_id,client_message_id,content) VALUES '+values+';')
        ids=[];after=before;through=None;page_sizes=[]
        while True:
            query=f'?afterMessageId={after}'+('' if through is None else f'&throughMessageId={through}')
            result=http(18982,f'/api/chat-rooms/{room}/messages/sync'+query,18)['data'];through=result['throughMessageId'];ids.extend(m['chatMessageId'] for m in result['messages']);page_sizes.append(len(result['messages']));after=result['nextAfterMessageId']
            if not result['hasNext']:break
        assert page_sizes==[20,20,5] and len(set(ids))==45
        print('PASS committed/not-published gap recovery: 20/20/5, unique=45',flush=True)
        a2=connect(18982,17);sockets.append(a2);time.sleep(.3)
        client=str(uuid.uuid4());c.send(f'SEND\ndestination:/app/chat-rooms/{room}/messages\ncontent-type:application/json\n\n'+json.dumps({'clientMessageId':client,'content':'after scale in'})+'\0');assert a2.message(client)['content']=='after scale in';c.message(client)
        print('PASS reconnect to surviving JVM and resume send',flush=True)
        subprocess.run(['docker','exec',REDIS,'redis-cli','CLIENT','KILL','TYPE','pubsub'],check=True,stdout=subprocess.DEVNULL)
        try:c.read();raise AssertionError('Subscriber loss did not close socket')
        except EOFError as closed:assert '1012' in str(closed)
        recovered=connect(18982,18);sockets.append(recovered)
        assert http(18982,f'/api/chat-rooms/{room}/messages/sync?afterMessageId={through}',18)['data']['messages']
        print('PASS Redis subscriber loss → 1012 → resubscribe/reconnect → DB recovery',flush=True)
        processes[1].kill();processes[1].wait(timeout=10)
        assert int(sql(f'SELECT COUNT(*) FROM chat_messages WHERE chat_room_id={room};').strip())==47
        print('PASS forced task termination; DB retains committed history (47)',flush=True)
        sql(f'DELETE FROM chat_rooms WHERE chat_room_id={room};')
    finally:
        for sock in sockets:
            try:sock.close()
            except OSError:pass
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=55)
                except subprocess.TimeoutExpired:process.kill();process.wait()
        for log in logs:log.close()
        stub.shutdown()
if __name__=='__main__':main()
