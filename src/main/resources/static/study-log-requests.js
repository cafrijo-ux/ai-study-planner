/* Request metadata only: never persist auth, CSRF, passwords or study contents. */
window.StudyLogRequests = (() => {
    const prefix = 'planner.study-request.v1.user1.goal.';
    const validKey = k => typeof k === 'string' && /^[a-z0-9][a-z0-9_-]{15,79}$/.test(k);
    function slot(goal) { if(!/^[1-9]\d*$/.test(String(goal))) throw new Error('목표를 선택하세요.'); return prefix+goal; }
    function pending(goal) {
        const raw=localStorage.getItem(slot(goal));if(raw===null)return null;
        const p=JSON.parse(raw);
        if(!p||p.version!==1||p.goalId!==Number(goal)||p.state!=='PENDING'||!validKey(p.key)||Object.keys(p).sort().join(',')!=='goalId,key,state,version')throw new Error('요청 키 보관 상태를 확인할 수 없습니다. 새 전송 전에 공부 기록을 확인하세요.');
        return p;
    }
    async function locked(goal, action) {
        if(!navigator.locks)throw new Error('안전한 요청 키 보관을 지원하는 localhost 또는 HTTPS 브라우저가 필요합니다. 전송하지 않았습니다.');
        return navigator.locks.request(slot(goal),action);
    }
    async function reserve(goal) { return locked(goal,()=>{
        const old=pending(goal);if(old)return {...old,existing:true};
        const bytes=crypto.getRandomValues(new Uint8Array(16));
        const p={version:1,goalId:Number(goal),state:'PENDING',key:'web_'+Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('')};
        localStorage.setItem(slot(goal),JSON.stringify(p));if(pending(goal)?.key!==p.key)throw new Error('요청 키 보관을 확인할 수 없어 전송하지 않았습니다.');return {...p,existing:false};
    }); }
    async function complete(goal,key){return locked(goal,()=>{const p=pending(goal);if(p&&p.key===key)localStorage.removeItem(slot(goal));});}
    async function lookup(goal) {
        const p=pending(goal);if(!p)throw new Error('보관된 요청 키가 없습니다. 기록 목록을 확인하세요.');
        const config=await fetch('/study-log-idempotency',{cache:'no-store'});
        if(!config.ok || (await config.json()).enabled!==true)throw new Error('서버 영속 멱등성이 비활성 또는 미지원 상태입니다. 기존 기록 목록에서 확인하세요. 새 POST는 보내지 않습니다.');
        const response=await fetch('/study-log-requests?goalId='+encodeURIComponent(goal)+'&requestKey='+encodeURIComponent(p.key),{cache:'no-store'});
        if(!response.ok)throw new Error('요청 결과 조회 실패 ('+response.status+'). 자동 재전송하지 않습니다.');
        const data=await response.json();
        if(data.goalId!==Number(goal)||data.requestKey!==p.key||!['COMPLETED','NOT_CONFIRMED'].includes(data.state))throw new Error('저장 결과의 목표·요청 키가 일치하지 않습니다.');
        if(data.state==='COMPLETED'){
            if(data.result?.userId!==1||!Number.isSafeInteger(data.result.logId)||data.result.logId<=0||data.result.idempotency?.requestKey!==p.key)throw new Error('저장 결과 형식이 올바르지 않습니다.');
            await complete(goal,p.key);
        }
        return data;
    }
    return Object.freeze({pending,reserve,complete,lookup});
})();
