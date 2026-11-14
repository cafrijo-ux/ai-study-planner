document.getElementById('loginForm').addEventListener('submit',async event=>{
    event.preventDefault();const form=event.currentTarget,button=document.getElementById('loginButton'),message=document.getElementById('loginMessage');
    if(button.disabled)return;button.disabled=true;message.textContent='로그인 확인 중…';
    try{
        const response=await fetch('/auth/login',{method:'POST',body:new URLSearchParams(new FormData(form))});
        if(!response.ok)throw new Error(response.status===429?'로그인 시도가 많습니다. 5분 뒤 다시 시도하세요.':response.status===401?'아이디 또는 비밀번호를 확인하세요.':response.status===403?'보안 토큰이 만료되었습니다. 다시 시도하세요.':'로그인 서버에 문제가 있습니다. 잠시 후 다시 시도하세요.');
        const data=await response.json();if(typeof data.message!=='string')throw new Error('로그인 응답을 확인할 수 없습니다.');
        PlannerAuth.resetCsrf();location.assign('/dashboard.html');
    }catch(error){message.textContent=error instanceof TypeError?'서버에 연결하지 못했습니다. 네트워크를 확인하고 다시 시도하세요.':error.message;button.disabled=false;}
});
