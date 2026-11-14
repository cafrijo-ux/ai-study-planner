/* Same-origin session + server-issued masked CSRF token. Never retry a write automatically. */
(() => {
    const nativeFetch=window.fetch.bind(window);
    let pendingToken=null;
    async function csrf() {
        if(!pendingToken) pendingToken=nativeFetch('/auth/csrf',{credentials:'same-origin',cache:'no-store'})
            .then(async response=>{if(!response.ok)throw new Error('보안 토큰을 받지 못했습니다. 다시 로그인해 주세요.');const data=await response.json();if(typeof data.token!=='string'||data.headerName!=='X-CSRF-TOKEN')throw new Error('보안 응답 형식 오류');return data;})
            .catch(error=>{pendingToken=null;throw error;});
        return pendingToken;
    }
    function notify(message){
        let notice=document.getElementById('sessionNotice');
        if(!notice){notice=document.createElement('p');notice.id='sessionNotice';notice.setAttribute('role','alert');document.body.prepend(notice);}
        notice.replaceChildren(document.createTextNode(message+' '));
        const link=document.createElement('a');link.href='/login.html';link.textContent='로그인 화면';notice.appendChild(link);
    }
    window.fetch=async (input,init={})=>{
        const url=new URL(input instanceof Request?input.url:input,location.href);
        if(url.origin!==location.origin)throw new Error('다른 서버로 인증 정보를 전송할 수 없습니다.');
        const method=String(init.method||(input instanceof Request?input.method:'GET')).toUpperCase();
        const headers=new Headers(init.headers||(input instanceof Request?input.headers:undefined));
        if(!['GET','HEAD','OPTIONS'].includes(method)||['/llm-coaching','/study-log-summary'].includes(url.pathname)){const token=await csrf();headers.set(token.headerName,token.token);}
        const response=await nativeFetch(input,{...init,headers,credentials:'same-origin',cache:'no-store'});
        if(response.status===401 && url.pathname!=='/auth/login'){pendingToken=null;notify('로그인이 만료되었습니다. 입력 내용은 화면에 유지됩니다.');}
        if(response.status===403){pendingToken=null;notify('권한 또는 보안 토큰을 확인해 주세요. 저장 요청은 자동 재시도하지 않았습니다.');}
        return response;
    };
    window.PlannerAuth={resetCsrf(){pendingToken=null;},async logout(){
        const response=await fetch('/auth/logout',{method:'POST'});
        if(!response.ok)throw new Error('로그아웃을 완료하지 못했습니다.');
        pendingToken=null;try{sessionStorage.removeItem('studyPlanner.currentGoalId');}catch(_){}
        location.assign('/login.html');
    }};
    document.addEventListener('DOMContentLoaded',()=>{
        if(location.pathname==='/login.html')return;
        const button=document.createElement('button');button.type='button';button.textContent='로그아웃';
        button.addEventListener('click',async()=>{button.disabled=true;try{await PlannerAuth.logout();}catch(_){notify('로그아웃에 실패했습니다. 연결을 확인해 주세요.');button.disabled=false;}});
        document.body.appendChild(button);
    });
})();
