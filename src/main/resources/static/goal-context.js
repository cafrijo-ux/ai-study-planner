/* 선택은 이 브라우저 탭과 URL에만 저장합니다. 서버 데이터는 바꾸지 않습니다. */
const GoalContext = (() => {
    const key = 'studyPlanner.currentGoalId';
    const parameter = new URLSearchParams(location.search).get('goalId');
    let remembered = null;
    try { remembered = sessionStorage.getItem(key); } catch (_) {}
    const id = parameter !== null ? parameter : remembered;
    const validId = value => /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value));
    function select(value) {
        if (!validId(value)) throw new Error('목표 번호를 확인하세요.');
        try { sessionStorage.setItem(key, String(value)); } catch (_) {}
    }
    if (parameter !== null && validId(parameter)) select(parameter);
    function url(path) {
        if (!validId(id)) throw new Error('먼저 현재 사용할 목표를 선택해 주세요.');
        const target = new URL(path, location.origin);
        target.searchParams.set('goalId', id);
        return target.pathname + target.search + target.hash;
    }
    function errorMessage(status) {
        return ({400:'입력값을 확인해 주세요.',401:'로그인이 필요합니다. 로그인한 주소와 현재 주소가 같은지 확인해 주세요.',
            403:'접근 권한 또는 보안 토큰을 확인해 주세요. 요청은 자동 재시도하지 않습니다.',
            429:'요청이 많습니다. 잠시 기다린 뒤 다시 시도해 주세요.',404:'요청한 사용자 또는 목표를 찾을 수 없습니다.',
            409:'이미 등록된 정보와 충돌합니다. 내용을 확인해 주세요.',
            500:'서버에서 저장을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.'})[status]
            || '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
    }
    async function readResponse(response) {
        let data;
        try { data = await response.json(); } catch (_) {
            throw new Error(response.ok ? '서버 응답 형식을 확인할 수 없습니다. 저장 여부를 확인한 뒤 같은 내용으로 다시 시도하세요.' : errorMessage(response.status));
        }
        if (!response.ok) throw new Error(errorMessage(response.status) +
            (response.status < 500 && typeof data?.message === 'string' ? ' ' + data.message : ''));
        return data;
    }
    async function scopedFetch(path, options) {
        const response = await fetch(url(path), options);
        if (!response.ok) throw new Error(errorMessage(response.status));
        return response;
    }
    function links() {
        if (!validId(id)) return;
        document.querySelectorAll('a[href]').forEach(a => {
            const target = new URL(a.getAttribute('href'), location.href);
            if (target.origin === location.origin && ['/dashboard.html','/study-log-form.html'].includes(target.pathname)) {
                target.searchParams.set('goalId', id); a.href = target.pathname + target.search + target.hash;
            }
        });
    }
    return {id, validId, select, url, readResponse, scopedFetch, links};
})();

window.GoalContext = GoalContext;

async function initializeGoalPicker() {
    const picker = document.getElementById('currentGoalSelect');
    const notice = document.getElementById('currentGoalNotice');
    const complete = document.getElementById('completeGoalLink');
    try {
        const [goals, subjects] = await Promise.all(['/goals','/subjects'].map(async path => GoalContext.readResponse(await fetch(path, {cache:'no-store'}))));
        if (!Array.isArray(goals) || !Array.isArray(subjects)) throw new Error('목표 목록 응답 형식이 올바르지 않습니다.');
        picker.replaceChildren(new Option('현재 사용할 목표를 선택하세요', ''));
        for (const goal of goals.filter(g => g.userId === 1)) {
            const count = subjects.filter(s => s.userId === 1 && s.goalId === goal.goalId).length;
            picker.appendChild(new Option(goal.goalId + '번 · ' + goal.goalName + (count ? '' : ' (미완성: 과목 없음)'), String(goal.goalId)));
        }
        picker.addEventListener('change', () => {
            if (!picker.value) return;
            GoalContext.select(picker.value);
            const target = new URL(location.href); target.searchParams.set('goalId', picker.value);
            location.assign(target.href); // 이전 목표의 비동기 응답/카드를 함께 폐기합니다.
        });
        const selected = goals.find(g => g.userId === 1 && String(g.goalId) === GoalContext.id);
        if (!selected) {
            notice.textContent = GoalContext.id ? '선택한 목표를 찾을 수 없습니다. 사용할 목표를 직접 선택하세요.' : '목표를 선택하면 해당 목표의 계획과 기록을 조회합니다.';
            return false;
        }
        picker.value = String(selected.goalId);
        const count = subjects.filter(s => s.userId === 1 && s.goalId === selected.goalId).length;
        notice.textContent = count ? selected.goalId + '번 목표를 사용 중입니다. 계획·학습 분석·코칭은 이 목표 기준이며, 공부 질문 기록과 시간 설정은 사용자 공통입니다.'
            : selected.goalId + '번 목표는 미완성입니다. 과목을 등록하면 미리보기를 사용할 수 있습니다. 기존 저장 계획은 그대로 조회할 수 있습니다.';
        complete.hidden = false;
        complete.textContent = count ? '이 목표에 과목 추가' : '이 목표의 과목 등록 완료하기';
        complete.href = '/survey-form.html?mode=add-subject&goalId=' + selected.goalId;
        GoalContext.links();
        return true;
    } catch (error) {
        notice.textContent = error instanceof TypeError ? '서버에 연결하지 못했습니다. 연결 상태를 확인하고 새로고침하세요.' : error.message;
        return false;
    }
}
