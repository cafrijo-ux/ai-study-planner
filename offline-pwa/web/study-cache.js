/* Local MOCK snapshots only. No network, credentials or real-data sync. */
(function (root) {
  'use strict';
  const kinds = new Set(['goals','subjects','month','day','logs','logIndex','summary']);
  const blocked = new Set(['password','passwd','cookie','cookies','session','jsessionid','csrf','csrftoken','token','apikey','authorization']);
  class CacheError extends Error { constructor(code) { super(code); this.code = code; } }
  function sensitive(value) {
    if (!value || typeof value !== 'object') return;
    for (const [key, child] of Object.entries(value)) {
      if (blocked.has(key.toLowerCase().replace(/[^a-z0-9]/g,''))) throw new CacheError('SENSITIVE_FIELD');
      sensitive(child);
    }
  }
  function validateKey(key) {
    if (!key || key.server !== 'https://mock.invalid' || !Number.isSafeInteger(key.userId) ||
        key.userId <= 0 || !Number.isSafeInteger(key.goalId) || key.goalId < 0 ||
        !kinds.has(key.kind)) throw new CacheError('REAL_DATA_DISABLED');
    if (['month','day','logs'].includes(key.kind)) {
      const regex = key.kind === 'month' ? /^\d{4}-(0[1-9]|1[0-2])$/ : /^\d{4}-(0[1-9]|1[0-2])-\d{2}$/;
      if (!regex.test(key.period)) throw new CacheError('INVALID_PERIOD');
      if (key.kind !== 'month' && (Number.isNaN(Date.parse(key.period)) ||
          new Date(key.period).toISOString().slice(0,10) !== key.period)) throw new CacheError('INVALID_PERIOD');
    } else if (key.period !== 'all') throw new CacheError('INVALID_PERIOD');
    if ((key.kind === 'goals') !== (key.goalId === 0)) throw new CacheError('INVALID_GOAL');
    return key;
  }
  function storageKey(key) {
    validateKey(key);
    return JSON.stringify([key.server,key.userId,key.goalId,key.kind,key.period]);
  }
  function validateRecord(record) {
    if (!record || record.schema !== 1 || record.source !== 'MOCK_FIXTURE' ||
        typeof record.syncedAt !== 'string' || Number.isNaN(Date.parse(record.syncedAt))) throw new CacheError('CORRUPT');
    validateKey(record.key);
    sensitive(record.payload);
    const p=record.payload, kind=record.key.kind;
    if (['goals','subjects','logs','logIndex'].includes(kind) ? !Array.isArray(p) :
        !p || typeof p !== 'object' || Array.isArray(p)) throw new CacheError('CORRUPT');
    if (kind === 'day' && (p.goalId !== record.key.goalId || p.planDate !== record.key.period ||
        (p.saved !== false && !['FINAL','DRAFT'].includes(p.planStatus)))) throw new CacheError('CORRUPT');
    if (kind === 'month' && (p.goalId !== record.key.goalId || p.month !== record.key.period ||
        !Array.isArray(p.plans) || p.plans.some(x=>!['FINAL','DRAFT'].includes(x.planStatus) ||
          x.goalId !== record.key.goalId || !(x.planDate || '').startsWith(record.key.period+'-')))) throw new CacheError('CORRUPT');
    if (kind === 'logs' && p.some(x=>x.userId !== record.key.userId || x.studyDate !== record.key.period)) throw new CacheError('CORRUPT');
    if (kind === 'subjects' && p.some(x=>x.userId !== record.key.userId || x.goalId !== record.key.goalId)) throw new CacheError('CORRUPT');
    if (JSON.stringify(record).length > 2*1024*1024) throw new CacheError('TOO_LARGE');
    return record;
  }
  const canonical = record => JSON.stringify({schema:record.schema,key:record.key,source:record.source,syncedAt:record.syncedAt,payload:record.payload});
  async function digest(text) {
    const bytes = await root.crypto.subtle.digest('SHA-256',new TextEncoder().encode(text));
    return Array.from(new Uint8Array(bytes),x=>x.toString(16).padStart(2,'0')).join('');
  }
  function errorCode(error) {
    if (error instanceof CacheError) return error.code;
    return error && error.name === 'QuotaExceededError' ? 'QUOTA' : 'UNAVAILABLE';
  }
  class MockSnapshotStore {
    constructor(name='planner-ipad-mock-v1') {
      if (!/^planner-ipad-mock(?:-test)?-v1$/.test(name)) throw new CacheError('INVALID_DATABASE');
      this.name=name; this.dbPromise=null;
    }
    open() {
      if (this.dbPromise) return this.dbPromise;
      this.dbPromise=new Promise((resolve,reject)=>{
        if (!root.indexedDB) return reject(new CacheError('UNAVAILABLE'));
        const request=root.indexedDB.open(this.name,1);
        request.onupgradeneeded=()=>{
          const db=request.result;
          if (!db.objectStoreNames.contains('snapshots')) db.createObjectStore('snapshots');
          if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta');
        };
        request.onerror=()=>reject(request.error);
        request.onblocked=()=>reject(new CacheError('BLOCKED'));
        request.onsuccess=()=>{
          const db=request.result;
          db.onversionchange=()=>{db.close();this.dbPromise=null;};
          resolve(db);
        };
      }).catch(e=>{this.dbPromise=null;throw e;});
      return this.dbPromise;
    }
    async activate(identity) {
      validateKey({...identity,goalId:0,kind:'goals',period:'all'});
      const db=await this.open();
      return new Promise((resolve,reject)=>{
        const tx=db.transaction(['meta','snapshots'],'readwrite'), meta=tx.objectStore('meta');
        const request=meta.get('active');
        request.onsuccess=()=>{
          const previous=request.result;
          if (previous && (previous.server !== identity.server || previous.userId !== identity.userId)) {
            tx.objectStore('snapshots').clear();
          }
          meta.put(identity,'active');
        };
        tx.oncomplete=()=>resolve();
        tx.onabort=()=>reject(tx.error || new CacheError('UNAVAILABLE'));
        tx.onerror=()=>{};
      });
    }
    async writeBatch(records) {
      if (!Array.isArray(records) || records.length > 1000) throw new CacheError('TOO_LARGE');
      // Validate and hash outside the transaction; keep IDB transactions synchronous.
      const prepared=await Promise.all(records.map(async r=>{
        validateRecord(r);
        return [storageKey(r.key),{...r,checksum:await digest(canonical(r))}];
      }));
      const db=await this.open();
      return new Promise((resolve,reject)=>{
        const tx=db.transaction(['snapshots','meta'],'readwrite');
        const active=tx.objectStore('meta').get('active');
        active.onsuccess=()=>{
          if (!active.result || records.some(r=>r.key.server !== active.result.server || r.key.userId !== active.result.userId)) {
            tx.abort(); return;
          }
          for (const [key,value] of prepared) tx.objectStore('snapshots').put(value,key);
        };
        tx.oncomplete=()=>resolve();
        tx.onabort=()=>reject(tx.error || new CacheError('IDENTITY_CHANGED'));
        tx.onerror=()=>{};
      });
    }
    async read(key) {
      const id=storageKey(key), db=await this.open();
      const record=await new Promise((resolve,reject)=>{
        const tx=db.transaction(['snapshots','meta'],'readonly');
        let value, allowed=false;
        const active=tx.objectStore('meta').get('active');
        active.onsuccess=()=>{allowed=active.result?.server===key.server && active.result?.userId===key.userId;};
        const req=tx.objectStore('snapshots').get(id);
        req.onsuccess=()=>{value=req.result;};
        tx.oncomplete=()=>allowed ? resolve(value) : reject(new CacheError('IDENTITY_CHANGED'));
        tx.onabort=()=>reject(tx.error || new CacheError('UNAVAILABLE'));
      });
      if (record === undefined) return {state:'missing'};
      try {
        validateRecord(record);
        if (storageKey(record.key)!==id || record.checksum!==await digest(canonical(record))) throw new CacheError('CORRUPT');
      } catch (_) { return {state:'corrupt',error:'CORRUPT'}; }
      const empty=Array.isArray(record.payload) ? record.payload.length===0 :
        record.key.kind==='day' && record.payload.saved===false;
      return {state:empty?'empty':'ready',record};
    }
    async clear(identity) {
      validateKey({...identity,goalId:0,kind:'goals',period:'all'});
      const db=await this.open(), prefix=JSON.stringify([identity.server,identity.userId]).slice(0,-1)+',';
      return new Promise((resolve,reject)=>{
        const tx=db.transaction('snapshots','readwrite'), req=tx.objectStore('snapshots').openCursor();
        req.onsuccess=()=>{
          const cursor=req.result;
          if (!cursor) return;
          if (String(cursor.key).startsWith(prefix)) cursor.delete();
          cursor.continue();
        };
        tx.oncomplete=()=>resolve();
        tx.onabort=()=>reject(tx.error || new CacheError('UNAVAILABLE'));
      });
    }
  }
  root.PlannerSnapshot={MockSnapshotStore,validateKey,validateRecord,storageKey,errorCode,CacheError};
  if (typeof module!=='undefined') module.exports=root.PlannerSnapshot;
})(globalThis);

