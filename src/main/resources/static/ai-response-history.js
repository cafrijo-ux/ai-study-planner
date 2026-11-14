/* Read-only saved snapshots. This module never calls an AI-generation endpoint. */
window.AiResponseHistory = (() => {
    function addInlineMarkdown(parent, text) {
        const pattern = /(\*\*[^*\n]+\*\*|`[^`\n]+`|\*[^*\n]+\*)/g;
        let lastIndex = 0;
        let match;

        while ((match = pattern.exec(text)) !== null) {
            if (match.index > lastIndex) {
                parent.appendChild(document.createTextNode(text.slice(lastIndex, match.index)));
            }

            const token = match[0];
            let element;
            if (token.startsWith("**")) {
                element = document.createElement("strong");
                element.textContent = token.slice(2, -2);
            } else if (token.startsWith("`")) {
                element = document.createElement("code");
                element.textContent = token.slice(1, -1);
            } else {
                element = document.createElement("em");
                element.textContent = token.slice(1, -1);
            }
            parent.appendChild(element);
            lastIndex = pattern.lastIndex;
        }

        if (lastIndex < text.length) {
            parent.appendChild(document.createTextNode(text.slice(lastIndex)));
        }
    }

    function renderMarkdown(text, target) {
        target.replaceChildren();
        const lines = String(text || "").replace(/\r\n/g, "\n").split("\n");
        let paragraph = [];
        let list = null;
        let listType = null;
        let codeLines = null;

        function flushParagraph() {
            if (paragraph.length > 0) {
                const p = document.createElement("p");
                paragraph.forEach((line, index) => {
                    if (index) p.appendChild(document.createElement("br"));
                    addInlineMarkdown(p, line);
                });
                target.appendChild(p);
                paragraph = [];
            }
        }

        function closeList() {
            list = null;
            listType = null;
        }

        function appendListItem(type, content, firstNumber) {
            if (!list || listType !== type) {
                closeList();
                list = document.createElement(type);
                if (type === "ol" && firstNumber && firstNumber !== 1) {
                    list.start = firstNumber;
                }
                target.appendChild(list);
                listType = type;
            }
            const li = document.createElement("li");
            addInlineMarkdown(li, content);
            list.appendChild(li);
        }

        for (const line of lines) {
            const trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                flushParagraph();
                closeList();
                if (codeLines === null) {
                    codeLines = [];
                } else {
                    const pre = document.createElement("pre");
                    const code = document.createElement("code");
                    code.textContent = codeLines.join("\n");
                    pre.appendChild(code);
                    target.appendChild(pre);
                    codeLines = null;
                }
                continue;
            }

            if (codeLines !== null) {
                codeLines.push(line);
                continue;
            }

            if (!trimmed) {
                flushParagraph();
                closeList();
                continue;
            }

            const heading = trimmed.match(/^(#{1,6})\s+(.+)$/);
            if (heading) {
                flushParagraph();
                closeList();
                const tagName = heading[1].length <= 1 ? "h3" : heading[1].length === 2 ? "h4" : "h5";
                const h = document.createElement(tagName);
                addInlineMarkdown(h, heading[2]);
                target.appendChild(h);
                continue;
            }

            if (/^(?:---+|\*\*\*+|___+)$/.test(trimmed)) {
                flushParagraph();
                closeList();
                target.appendChild(document.createElement("hr"));
                continue;
            }

            const bullet = trimmed.match(/^[-*+]\s+(.+)$/);
            const numbered = trimmed.match(/^(\d+)[.)]\s+(.+)$/);
            if (bullet || numbered) {
                flushParagraph();
                if (bullet) {
                    appendListItem("ul", bullet[1]);
                } else {
                    appendListItem("ol", numbered[2], Number(numbered[1]));
                }
                continue;
            }

            if (trimmed.startsWith("> ")) {
                flushParagraph();
                closeList();
                const quote = document.createElement("blockquote");
                addInlineMarkdown(quote, trimmed.slice(2));
                target.appendChild(quote);
                continue;
            }

            closeList();
            paragraph.push(trimmed);
        }

        flushParagraph();
        if (codeLines !== null) {
            const pre = document.createElement("pre");
            const code = document.createElement("code");
            code.textContent = codeLines.join("\n");
            pre.appendChild(code);
            target.appendChild(pre);
        }
    }


    function url(feature) {
        const path = '/ai-responses?feature=' + encodeURIComponent(feature);
        return typeof GoalContext !== 'undefined' && GoalContext.id ? GoalContext.url(path) : path;
    }
    const states = new WeakMap();
    const containers = new Set();
    const goal = () => typeof GoalContext !== 'undefined' && GoalContext.validId(GoalContext.id) ? Number(GoalContext.id) : null;
    const textNode = (tag, cls, text) => { const n=document.createElement(tag);if(cls)n.className=cls;n.textContent=String(text ?? '정보 없음');return n; };
    const modelName = row => row.requestedProvider === 'gemini' ? 'Gemini' : row.requestedProvider === 'gpt' ? 'GPT-4o-mini' : row.model || '모델 정보 없음';
    const sourceName = row => row.mockResponse === true && (row.testMode === 'AI_MOCK_H2_ONLY' || (row.testMode === 'AI_MOCK_DUAL_LEDGER_ONLY' && row.storageMode === 'MYSQL_DUAL_LEDGER_FAKEWIRE' && row.realAiCalls === 0))
        ? '격리 Mock · 실제 AI 아님' : ({PROVIDER:'실제 공급자 응답',RULE:'규칙 기반 응답 (AI 응답 아님)',UNKNOWN:'정보 없음'})[row.responseSource] || '정보 없음';
    const completion = row => row.responseComplete === true ? '완료' : row.responseComplete === false ? '미완료' : '완료 정보 없음';
    const stamp = value => typeof value === 'string' ? value.replace('T',' ') : '정보 없음';
    function validate(row, feature, selectedGoal) {
        if (!row || !Number.isSafeInteger(row.responseId) || row.responseId <= 0 || row.feature !== feature || typeof row.responseBody !== 'string') throw new Error('저장 응답 형식이 올바르지 않습니다.');
        if (selectedGoal !== null && row.goalId !== selectedGoal) throw new Error('선택한 목표와 저장 이력이 일치하지 않습니다.');
        return row;
    }
    async function read(path) {
        const response=await fetch(path,{method:'GET',cache:'no-store'});
        if(!response.ok)throw new Error(response.status===401 ? '세션이 만료되었습니다. 다시 로그인해 주세요.' : response.status===403 ? '저장 응답 조회 권한을 확인하세요.' : response.status===404 ? '저장된 응답 또는 조회 기능을 찾을 수 없습니다.' : '저장 응답을 조회하지 못했습니다. 다시 조회해 주세요.');
        return response.json();
    }
    function questionFor(row, questions) {
        const q=questions.get(row.questionId);
        return q && q.userId === row.userId && typeof q.question === 'string' ? q.question : null;
    }
    function renderCard(row, question, target) {
        const card=textNode('article','ai-response-card','');
        card.append(textNode('h3','','저장된 AI 답변'));
        const metadata=textNode('div','ai-response-metadata','');
        const fields=[['생성 시각(한국)',stamp(row.createdAt)],['목표',row.goalId ?? '사용자 공통'],['선택 모델',modelName(row)],['요청 제공업체',row.requestedProvider || '정보 없음'],['실제 제공업체',row.actualProvider || '해당 없음 / 정보 없음'],['실제 응답 모델',row.model || '정보 없음'],['출처',sourceName(row)],['완결 정보',completion(row)]];
        for(const [label,value] of fields){const p=document.createElement('p');p.append(textNode('strong','',label+': '),textNode('span','',value));metadata.append(p);}
        card.append(metadata);
        if(row.feature==='QUESTION'){
            const original=textNode('section','ai-original-question','');original.append(textNode('h4','','질문 원문'),textNode('p','',question || '이 응답과 연결된 질문 원문을 조회할 수 없습니다. 질문 기록 번호: '+(row.questionId ?? '정보 없음')));card.append(original);
        }
        const body=textNode('div','ai-response-body','');renderMarkdown(row.responseBody,body);card.append(body);target.replaceChildren(card);
    }
    async function load(container) {
        const selectedGoal = goal();
        const feature = container.dataset.aiHistory;
        const button = container.querySelector('button');
        const output = container.querySelector('[data-history-output]');
        const previous=states.get(container);
        if (button.disabled && previous?.goal===selectedGoal) return;
        const state={goal:selectedGoal,epoch:(previous?.epoch || 0)+1,detail:0,selected:null};states.set(container,state);containers.add(container);
        const current=()=>states.get(container)===state && goal()===selectedGoal;
        button.disabled = true;
        output.setAttribute('aria-busy', 'true');
        output.setAttribute('role','status');
        output.replaceChildren();
        output.textContent = '저장된 응답 조회 중 · AI 호출 없음';
        try {
            const rows = await read(url(feature));
            if(!current())return;
            if (!Array.isArray(rows)) throw new Error('저장 응답 형식이 올바르지 않습니다.');
            if (!rows.length) { output.textContent = '이 범위에 저장된 AI 응답이 없습니다. 기존 질문 기록은 별도 조회할 수 있습니다.'; return; }
            if(rows.length>20)throw new Error('저장 응답 목록 크기가 조회 계약과 다릅니다.');
            const ids=new Set();for(const row of rows){validate(row,feature,selectedGoal);if(ids.has(row.responseId))throw new Error('중복된 저장 응답 번호입니다.');ids.add(row.responseId);}
            rows.sort((a,b)=>String(b.createdAt || '').localeCompare(String(a.createdAt || '')) || b.responseId-a.responseId);
            const questions=new Map();let questionWarning='';
            if(feature==='QUESTION'){
                try{const legacy=await read('/study-questions');if(!Array.isArray(legacy))throw new Error('질문 원문 형식이 올바르지 않습니다.');for(const q of legacy)if(Number.isSafeInteger(q.questionId))questions.set(q.questionId,q);}
                catch(e){if(/세션|권한/.test(e.message))throw e;questionWarning='답변은 조회됐지만 질문 원문을 불러오지 못했습니다.';}
                if(!current())return;
            }
            output.replaceChildren();
            output.append(textNode('p','ai-history-count',`최근 저장 답변 ${rows.length}개 · 최신순 · 조회는 AI를 호출하지 않습니다.`));
            if(questionWarning)output.append(textNode('p','',questionWarning));
            const layout=textNode('div','ai-history-layout',''), list=textNode('div','ai-history-list',''), detail=textNode('div','ai-history-detail','');list.setAttribute('aria-label','저장된 AI 답변 목록');detail.setAttribute('aria-live','polite');layout.append(list,detail);output.append(layout);
            const buttons=new Map(),cache=new Map();
            async function select(row){
                if(!current() || state.selected===row.responseId)return;
                state.selected=row.responseId;const request=++state.detail;
                for(const [id,b] of buttons)b.setAttribute('aria-pressed',String(id===row.responseId));
                detail.removeAttribute('role');detail.replaceChildren(textNode('p','','선택한 저장 답변을 조회하는 중…'));detail.setAttribute('aria-busy','true');
                try{
                    const saved=cache.get(row.responseId) || validate(await read('/ai-responses/'+row.responseId),feature,selectedGoal);
                    if(!current() || request!==state.detail)return;
                    if(saved.responseId!==row.responseId || saved.questionId!==row.questionId || saved.userId!==row.userId)throw new Error('선택한 응답과 상세 정보가 일치하지 않습니다.');
                    cache.set(row.responseId,saved);renderCard(saved,questionFor(saved,questions),detail);
                }catch(e){if(current() && request===state.detail){state.selected=null;detail.replaceChildren(textNode('p','',e instanceof TypeError?'서버에 연결하지 못했습니다. 다시 조회해 주세요.':e.message));detail.setAttribute('role','alert');}}
                finally{if(current() && request===state.detail){detail.setAttribute('aria-busy','false');}}
            }
            for(const row of rows){
                const b=textNode('button','ai-history-item','');b.type='button';b.dataset.responseId=String(row.responseId);b.setAttribute('aria-pressed','false');
                const heading=textNode('span','ai-history-item-heading','');heading.append(textNode('strong','',modelName(row)),textNode('span','ai-history-completion',completion(row)));
                const question=questionFor(row,questions);b.append(heading,textNode('time','',stamp(row.createdAt).slice(0,16)),textNode('span','ai-history-summary',question ? [...question.replace(/\s+/g,' ')].slice(0,90).join('') : feature==='QUESTION'?'질문 기록 #'+(row.questionId ?? '정보 없음'):'저장된 코칭 응답'),textNode('span','ai-history-source',sourceName(row)));
                b.addEventListener('click',()=>select(row));buttons.set(row.responseId,b);list.append(b);
            }
            await select(rows[0]);
        } catch (error) {
            if(!current())return;
            output.textContent = error instanceof SyntaxError ? '저장 응답 형식이 올바르지 않습니다.' :
                error instanceof TypeError ? '서버 연결을 확인하세요. AI는 다시 호출하지 않았습니다.' : error.message;
            output.setAttribute('role','alert');
        } finally { if(current()){button.disabled = false; output.setAttribute('aria-busy', 'false');} }
    }
    function invalidate(){for(const container of containers){const state=states.get(container);if(state && state.goal!==goal()){states.delete(container);container.querySelector('[data-history-output]').replaceChildren(textNode('p','','목표가 바뀌었습니다. 저장 응답을 다시 조회하세요.'));container.querySelector('button').disabled=false;}}}
    for(const event of ['goalchange','goal-context-changed','popstate'])window.addEventListener(event,invalidate);
    function mount() {
        if (!document.getElementById('ai-response-history-styles')) {
            const style = document.createElement('style'); style.id = 'ai-response-history-styles';
            // Static scoped CSS; server values never become HTML or CSS.
            style.textContent = `
                [data-ai-history] { margin-top:18px; text-align:left; min-width:0; overflow-wrap:anywhere; }
                [data-ai-history] summary { cursor:pointer; padding:12px; background:#eef4ff; border:1px solid #dce5f8; border-radius:10px; color:#1e40af; font-weight:600; }
                [data-ai-history] button { border:1px solid #cbd5e1; border-radius:8px; padding:12px 16px; margin:12px 0; background:#eef4ff; color:#1e40af; cursor:pointer; max-width:100%; white-space:normal; transition:background-color .15s; }
                [data-ai-history] button:hover:not(:disabled) { background:#dce9ff; }
                [data-ai-history] button:disabled { opacity:.65; cursor:wait; }
                [data-ai-history] button:focus-visible,[data-ai-history] summary:focus-visible { outline:3px solid #93c5fd; outline-offset:3px; }
                [data-history-output] { margin-top:12px; text-align:left; }
                [data-history-output][aria-busy=true] { animation:history-pulse 1s ease-in-out infinite alternate; }
                @keyframes history-pulse { from { opacity:.6; } to { opacity:1; } }
                .ai-response-card { text-align:left; padding:18px; border:1px solid #dce5f8; border-radius:12px; background:#fff; min-width:0; }
                .ai-response-card > h3 { margin:0 0 14px; font-size:18px; color:#1e40af; }
                .ai-response-metadata { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px 18px; padding-bottom:16px; margin-bottom:16px; border-bottom:1px solid #e2e8f0; font-size:13px; line-height:1.6; color:#475569; }
                .ai-response-metadata p { margin:0; overflow-wrap:anywhere; }
                .ai-response-body { text-align:left; font-size:16px; line-height:1.85; overflow-wrap:anywhere; min-width:0; color:#253247; }
                .ai-response-body p { margin:0 0 14px; }
                .ai-response-body h3,.ai-response-body h4,.ai-response-body h5 { margin:20px 0 10px; line-height:1.5; color:#1e40af; }
                .ai-response-body h3 { font-size:20px; } .ai-response-body h4 { font-size:18px; } .ai-response-body h5 { font-size:16px; }
                .ai-response-body ul,.ai-response-body ol { padding-left:24px; margin:10px 0 16px; }
                .ai-response-body li { margin:6px 0; }
                .ai-response-body pre { white-space:pre-wrap; overflow-wrap:anywhere; padding:12px; border-radius:8px; background:#f1f5f9; }
                .ai-response-body code { font-family:Consolas,monospace; background:#f1f5f9; border-radius:4px; }
                .ai-response-body blockquote { margin:12px 0; padding-left:14px; border-left:3px solid #93c5fd; }
                .ai-response-body > :first-child { margin-top:0; }
                .ai-history-layout { display:grid; grid-template-columns:minmax(220px,.75fr) minmax(0,1.6fr); gap:18px; align-items:start; }
                .ai-history-list { display:grid; gap:9px; max-height:680px; overflow:auto; padding:2px; }
                .ai-history-count { color:#52647e; font-size:13px; margin:12px 0; }
                [data-ai-history] .ai-history-item { display:grid; gap:8px; margin:0; width:100%; padding:15px; text-align:left; background:#fff; color:#243b5b; border-color:#dae3ef; overflow-wrap:anywhere; white-space:normal; }
                [data-ai-history] .ai-history-item[aria-pressed=true] { background:#edf3ff; border-color:#315cde; box-shadow:inset 3px 0 #315cde; }
                .ai-history-item-heading { display:flex; flex-wrap:wrap; gap:6px; justify-content:space-between; align-items:center; }
                .ai-history-item time,.ai-history-source { font-size:12px; color:#5a6d88; }
                .ai-history-summary { font-weight:650; line-height:1.6; }
                .ai-history-completion { font-size:11px; padding:3px 7px; border-radius:20px; background:#e8eef8; }
                .ai-history-detail { min-width:0; }
                .ai-original-question { padding:14px; margin:0 0 18px; border-radius:12px; background:#f3f6fc; white-space:pre-wrap; overflow-wrap:anywhere; }
                .ai-original-question h4 { margin:0 0 8px; color:#233e67; }.ai-original-question p { margin:0;line-height:1.7; }
                .ai-response-card { animation:ai-detail-in .22s ease-out; }
                @keyframes ai-detail-in { from { opacity:.3; transform:translateY(5px); } to { opacity:1; transform:none; } }
                @media(max-width:900px) { .ai-history-layout { grid-template-columns:minmax(0,1fr); }.ai-history-list { max-height:360px; } }
                @media(max-width:600px) { .ai-response-metadata { grid-template-columns:minmax(0,1fr); } .ai-response-card { padding:12px; } }
                @media(prefers-reduced-motion:reduce) { [data-history-output][aria-busy=true],.ai-response-card { animation:none; } [data-ai-history] button { transition:none; } }
            `;
            document.head.appendChild(style);
        }
        document.querySelectorAll('[data-ai-history]').forEach(container => {
            container.querySelector('button').addEventListener('click', () => load(container));
        });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount);
    else mount();
    return {load, renderMarkdown};
})();
