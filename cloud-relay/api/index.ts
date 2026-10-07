import type { VercelRequest, VercelResponse } from "@vercel/node";
import { Redis } from "@upstash/redis";
import crypto from "node:crypto";

const redis = Redis.fromEnv();
const ownerToken = process.env.ATOMOS_OWNER_TOKEN || "";
const agentToken = process.env.ATOMOS_AGENT_TOKEN || "";
const commandTtl = Number(process.env.ATOMOS_COMMAND_TTL_SECONDS || 120);
const presenceTtl = Number(process.env.ATOMOS_PRESENCE_TTL_SECONDS || 30);
const syncWaitMs = Number(process.env.ATOMOS_SYNC_WAIT_MS || 15000);

const COMMANDS: Record<string,{commandId:number;commandHex:string;local:boolean}> = {
  "input.page.get": { commandId:0x0642, commandHex:"0x0642", local:false },
  "input.hdr.get": { commandId:0x0645, commandHex:"0x0645", local:false },
  "input.summary.get": { commandId:0x0648, commandHex:"0x0648", local:false },
  "state.application.get": { commandId:0x0640, commandHex:"0x0640", local:false },
  "state.monitor.get": { commandId:0x0641, commandHex:"0x0641", local:false },
  "state.input.get": { commandId:0x0642, commandHex:"0x0642", local:false },
  "state.record.get": { commandId:0x0643, commandHex:"0x0643", local:false },
  "state.output.get": { commandId:0x0644, commandHex:"0x0644", local:false },
  "state.inputHdr.get": { commandId:0x0645, commandHex:"0x0645", local:false },
  "state.playbackFormat.get": { commandId:0x0646, commandHex:"0x0646", local:false },
  "state.audioMeters.get": { commandId:0x0647, commandHex:"0x0647", local:false },
  "state.inputOutput.get": { commandId:0x0648, commandHex:"0x0648", local:false },
  "state.monitorAssist.get": { commandId:0x0649, commandHex:"0x0649", local:false },
  "monitor.focus.set": { commandId:0x0262, commandHex:"0x0262", local:true },
  "monitor.zebra.set": { commandId:0x026A, commandHex:"0x026A", local:true },
  "monitor.falseColor.set": { commandId:0x026E, commandHex:"0x026E", local:true },
  "monitor.blueOnly.set": { commandId:0x0270, commandHex:"0x0270", local:true },
  "scopes.enabled.set": { commandId:0x0258, commandHex:"0x0258", local:true },
  "scopes.mode.set": { commandId:0x025A, commandHex:"0x025A", local:true },
  "scopes.size.set": { commandId:0x025C, commandHex:"0x025C", local:true },
  "guides.frame.enabled.set": { commandId:0x044E, commandHex:"0x044E", local:true },
  "guides.safeArea.set": { commandId:0x0277, commandHex:"0x0277", local:true },
  "monitor.anamorphic.set": { commandId:0x0279, commandHex:"0x0279", local:true },
  "monitor.mode.set": { commandId:0x02BC, commandHex:"0x02BC", local:true }
};

function bearer(req:VercelRequest):string {
  const h = String(req.headers.authorization || "");
  return h.startsWith("Bearer ") ? h.slice(7) : "";
}
function requireOwner(req:VercelRequest,res:VercelResponse):boolean {
  if (!ownerToken || bearer(req)!==ownerToken) { res.status(401).json({ok:false,error:"Unauthorized owner"}); return false; }
  return true;
}
function requireAgent(req:VercelRequest,res:VercelResponse):boolean {
  if (!agentToken || bearer(req)!==agentToken) { res.status(401).json({ok:false,error:"Unauthorized agent"}); return false; }
  return true;
}
function body(req:VercelRequest):any { return typeof req.body === "string" ? JSON.parse(req.body || "{}") : (req.body || {}); }
const sleep=(ms:number)=>new Promise(r=>setTimeout(r,ms));
const presenceKey=(id:string)=>`atomos:device:${id}:presence`;
const queueKey=(id:string)=>`atomos:device:${id}:queue`;
const requestKey=(id:string)=>`atomos:request:${id}`;

async function enqueue(deviceId:string,action:string,payload:any={}) {
  const spec=COMMANDS[action]; if(!spec) throw new Error(`Unknown action: ${action}`);
  const requestId=crypto.randomUUID();
  const cmd={type:"agent.command",requestId,deviceId,action,payload,...spec,createdAt:new Date().toISOString()};
  await redis.set(requestKey(requestId),{status:"pending",...cmd},{ex:commandTtl});
  await redis.rpush(queueKey(deviceId),JSON.stringify(cmd));
  await redis.expire(queueKey(deviceId),commandTtl);
  return cmd;
}
async function waitResult(requestId:string,waitMs=syncWaitMs) {
  const until=Date.now()+waitMs;
  while(Date.now()<until){
    const value:any=await redis.get(requestKey(requestId));
    if(value && value.status==="complete") return value;
    await sleep(300);
  }
  return null;
}
async function submitAndWait(res:VercelResponse,deviceId:string,action:string,payload:any={}) {
  const presence:any=await redis.get(presenceKey(deviceId));
  if(!presence) return res.status(503).json({ok:false,deviceId,action,error:"Android agent offline"});
  const cmd=await enqueue(deviceId,action,payload);
  const result=await waitResult(cmd.requestId);
  if(!result) return res.status(504).json({ok:false,deviceId,action,requestId:cmd.requestId,error:"Timed out waiting for Android agent"});
  return res.status(result.ok?200:502).json(result);
}

export default async function handler(req:VercelRequest,res:VercelResponse) {
  try {
    const path=String(req.url || "/").split("?")[0];
    if(path==="/api/health" || path==="/api/index") return res.json({ok:true,service:"AtomRemote Cloud Relay",phase:"10-12",time:new Date().toISOString()});

    if(path==="/api/agent/hello" && req.method==="POST") {
      if(!requireAgent(req,res)) return;
      const b=body(req); const deviceId=String(b.deviceId||""); if(!deviceId) return res.status(400).json({ok:false,error:"deviceId required"});
      const p={deviceId,deviceName:b.deviceName||deviceId,model:b.model,firmware:b.firmware,lastSeen:new Date().toISOString()};
      await redis.set(presenceKey(deviceId),p,{ex:presenceTtl}); await redis.sadd("atomos:devices",deviceId);
      return res.json({ok:true,device:p,pollAfterMs:600});
    }
    if(path==="/api/agent/poll" && req.method==="GET") {
      if(!requireAgent(req,res)) return;
      const deviceId=String(req.query.deviceId||""); if(!deviceId) return res.status(400).json({ok:false,error:"deviceId required"});
      const old:any=await redis.get(presenceKey(deviceId));
      await redis.set(presenceKey(deviceId),{...(old||{deviceId}),lastSeen:new Date().toISOString()},{ex:presenceTtl});
      const raw:any=await redis.lpop(queueKey(deviceId));
      if(!raw) return res.status(204).end();
      return res.json(typeof raw==="string"?JSON.parse(raw):raw);
    }
    if(path==="/api/agent/result" && req.method==="POST") {
      if(!requireAgent(req,res)) return;
      const b=body(req); if(!b.requestId||!b.deviceId) return res.status(400).json({ok:false,error:"requestId and deviceId required"});
      await redis.set(requestKey(String(b.requestId)),{status:"complete",...b,completedAt:new Date().toISOString()},{ex:commandTtl});
      return res.json({ok:true});
    }

    if(path==="/api/v1/devices" && req.method==="GET") {
      if(!requireOwner(req,res)) return;
      const ids=await redis.smembers("atomos:devices"); const devices=[] as any[];
      for(const id of ids){const p=await redis.get(presenceKey(String(id))); if(p) devices.push(p);}
      return res.json({ok:true,devices});
    }
    const reqMatch=path.match(/^\/api\/v1\/requests\/([^/]+)$/);
    if(reqMatch && req.method==="GET") { if(!requireOwner(req,res)) return; const v=await redis.get(requestKey(reqMatch[1])); return v?res.json(v):res.status(404).json({ok:false,error:"Request not found"}); }
    const actionMatch=path.match(/^\/api\/v1\/devices\/([^/]+)\/actions$/);
    if(actionMatch && req.method==="POST") { if(!requireOwner(req,res)) return; const b=body(req); const cmd=await enqueue(actionMatch[1],String(b.action||""),b.payload||{}); return res.status(202).json({ok:true,queued:true,requestId:cmd.requestId,deviceId:cmd.deviceId,action:cmd.action}); }

    const m=path.match(/^\/api\/v1\/devices\/([^/]+)\/(.+)$/);
    if(m && requireOwner(req,res)) {
      const deviceId=m[1], suffix=m[2]; let action=""; let payload:any={};
      const gets:Record<string,string>={
        "input":"input.page.get","input/hdr":"input.hdr.get","input/summary":"input.summary.get",
        "state/application":"state.application.get","state/monitor":"state.monitor.get","state/input":"state.input.get",
        "state/record":"state.record.get","state/output":"state.output.get","state/input-hdr":"state.inputHdr.get",
        "state/playback-format":"state.playbackFormat.get","state/audio-meters":"state.audioMeters.get",
        "state/input-output":"state.inputOutput.get","state/monitor-assist":"state.monitorAssist.get"
      };
      const posts:Record<string,[string,(b:any)=>any]>={
        "monitor/focus":["monitor.focus.set",b=>({enabled:Boolean(b.enabled)})],
        "monitor/zebra":["monitor.zebra.set",b=>({enabled:Boolean(b.enabled)})],
        "monitor/false-color":["monitor.falseColor.set",b=>({enabled:Boolean(b.enabled)})],
        "monitor/blue-only":["monitor.blueOnly.set",b=>({enabled:Boolean(b.enabled)})],
        "scopes/enabled":["scopes.enabled.set",b=>({enabled:Boolean(b.enabled)})],
        "scopes/mode":["scopes.mode.set",b=>({mode:String(b.mode||"")})],
        "scopes/size":["scopes.size.set",b=>({size:String(b.size||"")})],
        "guides/frame":["guides.frame.enabled.set",b=>({enabled:Boolean(b.enabled)})],
        "guides/safe-area":["guides.safeArea.set",b=>({mode:Number(b.mode)})],
        "monitor/anamorphic":["monitor.anamorphic.set",b=>({mode:Number(b.mode)})],
        "monitor/mode":["monitor.mode.set",b=>({mode:Number(b.mode)})]
      };
      if(req.method==="GET" && gets[suffix]) action=gets[suffix];
      if(req.method==="POST" && posts[suffix]) { const [a,fn]=posts[suffix]; action=a; payload=fn(body(req)); }
      if(action) return submitAndWait(res,deviceId,action,payload);
    }

    return res.status(404).json({ok:false,error:"Route not found",path});
  } catch(error) {
    return res.status(500).json({ok:false,error:error instanceof Error?error.message:"Cloud relay failure"});
  }
}
