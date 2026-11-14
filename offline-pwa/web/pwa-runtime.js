(function () {
  'use strict';
  const state={online:navigator.onLine,shellReady:false,version:null,waiting:false,error:null};
  const store=new PlannerSnapshot.MockSnapshotStore();
  let identity={server:'https://mock.invalid',userId:1};
  let registrationRef;
  async function result(fn) {
    try { return JSON.stringify({ok:true,value:await fn()}); }
    catch(e) {return JSON.stringify({ok:false,error:PlannerSnapshot.errorCode(e)});}
  }
  function ask(worker) {
    return new Promise((resolve,reject)=>{
      if (!worker) return reject(new Error('NO_CONTROLLER'));
      const channel=new MessageChannel(), timer=setTimeout(()=>reject(new Error('STATUS_TIMEOUT')),3000);
      channel.port1.onmessage=e=>{clearTimeout(timer);channel.port1.close();resolve(e.data);};
      worker.postMessage({type:'STATUS'},[channel.port2]);
    });
  }
  async function refresh() {
    state.online=navigator.onLine;
    state.waiting=!!registrationRef?.waiting;
    try {
      const status=await ask(navigator.serviceWorker?.controller);
      state.shellReady=status.ready===true;
      state.version=status.version;
    } catch (_) {state.shellReady=false;}
  }
  window.addEventListener('online',refresh);
  window.addEventListener('offline',refresh);
  window.PlannerOfflineBridge={
    status:()=>JSON.stringify({...state,online:navigator.onLine}),
    activate:json=>result(async()=>{identity=JSON.parse(json);await store.activate(identity);return true;}),
    read:json=>result(()=>store.read(JSON.parse(json))),
    writeBatch:json=>result(async()=>{await store.writeBatch(JSON.parse(json));return true;}),
    clear:()=>result(async()=>{await store.clear(identity);return true;}),
    refresh:()=>result(async()=>{await refresh();return state;}),
  };
  window.PlannerPwaStart=async function () {
    if (!('serviceWorker' in navigator) || !window.isSecureContext) {
      state.error='SECURE_CONTEXT_REQUIRED';return;
    }
    try {
      const base=new URL('.',document.baseURI);
      if (base.pathname!=='/ipad/') throw new Error('PWA_SCOPE_MUST_BE_IPAD');
      const registration=await navigator.serviceWorker.register(new URL('pwa-sw.js',base),{scope:base.pathname,updateViaCache:'none'});
      registrationRef=registration;
      function watch() {
        state.waiting=!!registration.waiting;
        const installing=registration.installing;
        if (installing) installing.addEventListener('statechange',()=>{
          state.waiting=!!registration.waiting;
          if (installing.state==='redundant') state.error='INSTALL_FAILED';
          refresh();
        });
      }
      registration.addEventListener('updatefound',watch);watch();
      if (registration.active && navigator.onLine) {
        // One read-only app-shell update check; never a data/AI request retry.
        registration.update().catch(()=>{});
      }
      navigator.serviceWorker.addEventListener('controllerchange',refresh);
      await Promise.race([
        navigator.serviceWorker.ready,
        new Promise((_,reject)=>setTimeout(()=>reject(new Error('INSTALL_TIMEOUT')),90000)),
      ]);
      if (!navigator.serviceWorker.controller) {
        await new Promise((resolve,reject)=>{
          const timer=setTimeout(()=>reject(new Error('CONTROLLER_TIMEOUT')),5000);
          navigator.serviceWorker.addEventListener('controllerchange',()=>{clearTimeout(timer);resolve();},{once:true});
        });
      }
      await refresh();
      const expected=document.querySelector?.('meta[name="planner-build"]')?.content;
      if (state.shellReady && expected && expected!==state.version) {
        // An uncontrolled first page may have come from a concurrent deploy.
        // Navigate once to the active worker's complete HTML/bundle pair.
        window.location.replace(new URL('index.html',base).href);
        return false;
      }
    } catch (e) {
      // Offline cold starts can reject an update fetch while an existing
      // controller and complete cache are still usable. Inspect them first.
      await refresh();
      state.error=state.shellReady?null:(e.message || 'INSTALL_FAILED');
    }
  };
})();

