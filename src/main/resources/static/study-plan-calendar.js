/* Stored calendar + explicit preview overlays. This module NEVER sends POST. */
(() => {
    'use strict';
    const palette = ['#72a7ff','#b39aff','#6ad7aa','#f2b56b','#eb92c3','#65ccdc','#c6ca71','#ee9690'];
    const color = id => Number.isSafeInteger(id) && id > 0 ? palette[(id - 1) % palette.length] : '#8b95a5';
    const pad = n => String(n).padStart(2, '0');
    const dateKey = d => `${d.getUTCFullYear()}-${pad(d.getUTCMonth()+1)}-${pad(d.getUTCDate())}`;
    const dateOf = value => {
        if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('계획 날짜 형식을 확인하세요.');
        const d = new Date(value + 'T00:00:00Z');
        if (!Number.isFinite(+d) || dateKey(d) !== value || d.getUTCFullYear() < 1970 || d.getUTCFullYear() > 2100) throw new Error('조회 가능한 날짜는 1970~2100년입니다.');
        return d;
    };
    function today() {
        const parts = new Intl.DateTimeFormat('en', {timeZone:'Asia/Seoul', year:'numeric', month:'2-digit', day:'2-digit'}).formatToParts(new Date());
        const p = Object.fromEntries(parts.map(x => [x.type,x.value]));
        return `${p.year}-${p.month}-${p.day}`;
    }
    const monthOf = day => day.slice(0,7);
    const shift = (day, n) => {const d=dateOf(day); d.setUTCDate(d.getUTCDate()+n); return dateKey(d);};
    const clock = v => typeof v === 'string' ? (/^(?:\d{4}-\d{2}-\d{2}[ T])?(\d{2}:\d{2})(?::\d{2})?$/.exec(v)?.[1] || v) : '정보 없음';
    function el(tag, cls, text) {const n=document.createElement(tag); if(cls)n.className=cls; if(text !== undefined)n.textContent=String(text); return n;}
    function button(text, action, cls='') {const b=el('button',cls,text); b.type='button'; b.addEventListener('click',action); return b;}
    function plan(raw, goal, source) {
        if (!raw || typeof raw !== 'object' || raw.goalId !== goal) throw new Error('선택 목표와 서버 계획이 일치하지 않습니다.');
        const date=raw.planDate ?? raw.scheduleDate; dateOf(date);
        if (raw.scheduleDate != null && raw.scheduleDate !== date) throw new Error('서버 계획 날짜가 서로 다릅니다.');
        for (const k of ['studyMinutes','carryoverMinutes','newStudyMinutes']) if (typeof raw[k] !== 'number' || !Number.isFinite(raw[k]) || raw[k] < 0) throw new Error('공부·보충·신규 시간 정보를 확인할 수 없습니다.');
        if (source === 'saved' && ((raw.saved !== true && raw.planSaved !== true) || !['DRAFT','FINAL'].includes(raw.planStatus))) throw new Error('서버 저장 상태를 확인할 수 없습니다.');
        if (source === 'preview' && raw.previewMode !== true) throw new Error('서버의 미리보기 표식을 확인할 수 없습니다.');
        const slots=raw.timeSlots ?? [];
        if (!Array.isArray(slots) || slots.length > 500 || slots.some(s => !s || typeof s !== 'object' || Array.isArray(s))) throw new Error('시간표 항목 형식이 올바르지 않습니다.');
        for (const s of slots) for (const k of ['startTime','endTime']) if (typeof s[k] === 'string' && /^\d{4}-\d{2}-\d{2}/.test(s[k]) && !s[k].startsWith(date)) throw new Error('시간표 구간의 날짜가 선택 날짜와 다릅니다.');
        if (raw.subjectPlans != null && !Array.isArray(raw.subjectPlans)) throw new Error('과목별 배정 형식을 확인하세요.');
        return {raw, date, source, slots, status:source === 'saved' ? `${raw.planStatus} · 서버 저장본` : source === 'preview' ? '미리보기 · 미저장' : '계산 계획 · 미저장'};
    }
    function subjects(p) {
        const groups=new Map();
        for (const s of p.slots) {
            if (s.type === '휴식' || s.type === '다른 일정') continue;
            const id=Number.isSafeInteger(s.subjectId) && s.subjectId > 0 ? s.subjectId : null;
            const name=typeof s.subjectName === 'string' && s.subjectName ? s.subjectName : '과목명 정보 없음';
            const key=id === null ? `unknown:${name}` : `id:${id}`;
            if (!groups.has(key)) groups.set(key,{id,name,key,minutes:0,measured:true});
            const g=groups.get(key); if (typeof s.minutes === 'number' && Number.isFinite(s.minutes) && s.minutes >= 0) g.minutes+=s.minutes; else g.measured=false;
        }
        for (const g of groups.values()) {
            const allocation=(p.raw.subjectPlans ?? []).find(s => s && g.id !== null && s.subjectId === g.id);
            if (allocation && typeof allocation.studyMinutes === 'number' && Number.isFinite(allocation.studyMinutes)) {g.minutes=allocation.studyMinutes; g.measured=true;}
        }
        return [...groups.values()];
    }
    class CalendarView {
        constructor(root, options={}) {
            this.root=root;
            this.getGoal=options.getGoal || (() => ({id:Number(GoalContext.id), name:document.querySelector('#currentGoalSelect option:checked')?.textContent || `선택 목표 ${GoalContext.id}`}));
            this.fetcher=options.fetcher || ((path,init) => fetch(GoalContext.url(path),init));
            this.month=monthOf(today()); this.selected=today(); this.cache=new Map(); this.pending=new Map(); this.overlays=new Map(); this.knownEmpty=new Set();
            this.records=[]; this.seq=0; this.detailSeq=0; this.loaded=false; this.coverage=false; this.busy=false; this.unsupported=false; this.error=''; this.source='saved'; this.subject=null; this.goal=null;
            this.logRecords=[];this.logCoverage=false;this.logError='';this.history=null;this.historyPending=null;
            this.render();
        }
        syncGoal() {
            const g=this.getGoal();
            if (!g || !Number.isSafeInteger(g.id) || g.id <= 0) throw new Error('먼저 사용할 학습 목표를 선택해 주세요.');
            if (this.goal?.id !== g.id) {
                ++this.seq; ++this.detailSeq; this.cache.clear(); this.pending.clear(); this.overlays.clear(); this.knownEmpty.clear(); this.records=[]; this.coverage=false; this.loaded=false; this.unsupported=false; this.source='saved'; this.subject=null;
                this.logRecords=[];this.logCoverage=false;this.logError='';this.history=null;this.historyPending=null;
            }
            this.goal=g; return g;
        }
        async read(path, goal) {
            const url=new URL(path, location.origin); url.searchParams.set('goalId',goal);
            const r=await this.fetcher(url.pathname+url.search,{method:'GET',cache:'no-store'});
            if (!r.ok) {const e=new Error(({401:'로그인이 필요합니다.',403:'조회 권한 또는 서버 지원 상태를 확인하세요.'})[r.status] || `일정 조회 실패 (${r.status}). 자동 재시도하지 않습니다.`);e.status=r.status;throw e;}
            return r.json();
        }
        async load(force=false) {
            let g;
            try {g=this.syncGoal();} catch(e){++this.seq;this.goal=null;this.records=[];this.overlays.clear();this.logRecords=[];this.logCoverage=false;this.history=null;this.error=e.message; this.render();return;}
            if(this.unsupported && !force){this.busy=false;this.coverage=false;this.records=[];this.render();return;}
            const month=this.month, key=`${g.id}/${month}`, seq=++this.seq;
            if (force) ++this.detailSeq;
            const oldSaved = force ? [...this.overlays.entries()].filter(([,p])=>p.source==='saved' && p.date.startsWith(month+'-')) : [];
            this.busy=true; this.error=''; this.coverage=false; this.records=[]; this.render();
            const historyRequest=this.loadHistory(g,seq,force);
            let request;
            try {
                let rows=this.cache.get(key);
                if (force) {this.cache.delete(key);this.pending.delete(key);rows=null;}
                if (!rows) {
                    request=this.pending.get(key);
                    if (!request) {request=this.read('/study-reschedule-calendar?month='+month,g.id);this.pending.set(key,request);}
                    const raw=await request;
                    if (raw?.goalId !== g.id || raw.month !== month || !Array.isArray(raw.plans) || raw.plans.length > 31) throw new Error('월간 응답의 목표·월·계획 개수를 확인하세요.');
                    const dates=new Set();rows=raw.plans.map(p => {if(p.saved !== true)throw new Error('월간 저장 표식을 확인할 수 없습니다.');const value=plan(p,g.id,'saved');if (!value.date.startsWith(month+'-') || dates.has(value.date)) throw new Error('월간 계획 날짜가 다르거나 중복됐습니다.');dates.add(value.date);return value;});
                }
                if (seq !== this.seq || this.getGoal()?.id !== g.id) return;
                if (this.cache.size >= 6) this.cache.delete(this.cache.keys().next().value);
                this.cache.set(key,rows); this.records=rows; this.coverage=true; this.loaded=true; this.unsupported=false;
                for (const [key,p] of oldSaved) if(this.overlays.get(key)===p)this.overlays.delete(key);
            } catch(e) {
                if (seq !== this.seq || this.getGoal()?.id !== g.id) return;
                this.error=[404,501].includes(e.status) ? '월간 조회 미지원 서버입니다. 날짜를 선택하면 저장본만 조회합니다. 다른 날짜는 미조회입니다.' : e.message;
                this.unsupported=[404,501].includes(e.status);
                this.loaded=true;
            } finally {
                await historyRequest;
                if (request && this.pending.get(key) === request) this.pending.delete(key);
                if (seq === this.seq && this.getGoal()?.id === g.id) {this.busy=false;this.render();}
            }
        }
        async loadHistory(g,seq,force) {
            if(this.history?.goal===g.id && !force){this.logRecords=this.history.rows;this.logCoverage=true;return;}
            this.logCoverage=false;this.logError='';
            const pending=(!force && this.historyPending) || Promise.all([this.read('/subjects',g.id),this.read('/study-logs',g.id)]);
            this.historyPending=pending;
            try{
                const [rawSubjects,rawLogs]=await pending;
                if(seq!==this.seq || this.getGoal()?.id!==g.id)return;
                if(!Array.isArray(rawSubjects) || !Array.isArray(rawLogs) || rawLogs.length>50000)throw new Error('과목·공부 기록 조회 형식 또는 크기를 확인하세요.');
                const owned=new Map(rawSubjects.filter(s=>s && s.goalId===g.id && Number.isSafeInteger(s.subjectId) && s.subjectId>0 && Number.isSafeInteger(s.userId)).map(s=>[s.subjectId,s]));
                const rows=[];
                for(const log of rawLogs){
                    const subject=owned.get(log?.subjectId);
                    if(!subject || log.userId!==subject.userId)continue;
                    if(log.goalId!=null && log.goalId!==g.id)throw new Error('공부 기록의 목표 정보가 과목과 다릅니다.');
                    dateOf(log.studyDate);
                    if(!Number.isSafeInteger(log.logId) || log.logId<=0)throw new Error('공부 기록 번호를 확인하세요.');
                    rows.push({...log,subjectName:subject.subjectName || '과목명 정보 없음'});
                }
                this.logRecords=rows.sort((a,b)=>b.studyDate.localeCompare(a.studyDate)||b.logId-a.logId);
                this.logCoverage=true;this.history={goal:g.id,rows:this.logRecords};
            }catch(e){if(seq===this.seq && this.getGoal()?.id===g.id){this.logRecords=[];this.logCoverage=false;this.logError=e.message;}}
            finally{if(this.historyPending===pending)this.historyPending=null;}
        }
        logs(date) {return this.logRecords.filter(r=>r.studyDate===date);}
        measuredMinutes(rows) {return rows.every(r=>typeof r.studyMinutes==='number' && Number.isFinite(r.studyMinutes) && r.studyMinutes>=0) ? rows.reduce((sum,r)=>sum+r.studyMinutes,0) : null;}
        actual(date,scheduled) {
            const section=el('section','pc-actual-records');section.append(el('h4','','실제 공부 기록'));
            if(!this.logCoverage){section.append(el('p','pc-empty',this.logError?'공부 기록 조회 실패 · '+this.logError:'공부 기록은 아직 조회되지 않았습니다.'));return section;}
            const rows=this.logs(date),minutes=this.measuredMinutes(rows);
            if(!rows.length){section.append(el('p','pc-empty','이 목표에 연결된 공부 기록 없음'));return section;}
            section.append(el('p','pc-actual-total',`${rows.length}건 · ${minutes===null?'시간 정보 없음':minutes+'분'}`));
            if(scheduled?.source==='saved' && scheduled.raw.planStatus==='FINAL' && minutes!==null){
                const target=scheduled.raw.studyMinutes;
                if(typeof target==='number')section.append(el('p','pc-hint',`시간 기준 비교 · 예정 ${target}분 / 기록 ${minutes}분 · ${minutes>=target?'계획 시간 충족':'계획 시간 미달'} (서버 저장 상태 변경 없음)`));
            }
            for(const log of rows){
                const card=el('article','pc-record');card.style.setProperty('--subject-color',color(log.subjectId));
                card.append(el('strong','',log.subjectName),el('span','pc-record-minutes',typeof log.studyMinutes==='number'?`${log.studyMinutes}분`:'시간 정보 없음'));
                const scores=[['이해도','understandingScore'],['집중도','focusScore'],['피로도','fatigueScore']].map(([label,key])=>`${label} ${typeof log[key]==='number'?log[key]+'/5':'정보 없음'}`).join(' · ');
                card.append(el('p','pc-hint',scores));if(typeof log.studyContent==='string' && log.studyContent)card.append(el('p','pc-record-content',log.studyContent));
                section.append(card);
            }return section;
        }
        async select(date) {
            dateOf(date); const g=this.syncGoal(),seq=++this.detailSeq; this.selected=date;this.subject=null;this.source='saved';
            if (monthOf(date) !== this.month) {this.month=monthOf(date); await this.load();}
            if(this.getGoal()?.id !== g.id || this.selected !== date)return;
            this.render();
            if (this.coverage || this.busy) return;
            try {
                const raw=await this.read('/study-reschedule-saved?date='+date,g.id);
                if (seq !== this.detailSeq || this.selected !== date || this.getGoal()?.id !== g.id) return;
                if (raw.saved === false) {if (raw.goalId != null && raw.goalId !== g.id || raw.planDate != null && raw.planDate !== date) throw new Error('저장본 조회 대상이 다릅니다.');this.knownEmpty.add(date);}
                else {const p=plan(raw,g.id,'saved');if (p.date !== date) throw new Error('저장본의 날짜가 다릅니다.'); this.overlays.set(`saved/${date}`,p);}
                this.render();
            } catch(e){if (seq === this.detailSeq && this.selected === date && this.getGoal()?.id === g.id){this.error=e.message;this.render();}}
        }
        move(amount) {
            const d=dateOf(this.month+'-01'); d.setUTCMonth(d.getUTCMonth()+amount);
            if (d.getUTCFullYear() < 1970 || d.getUTCFullYear() > 2100) return;
            this.month=monthOf(dateKey(d));this.selected=this.month+'-01';++this.detailSeq;this.subject=null;this.source='saved';this.load();
        }
        async show(raw, source, refresh=false) {
            const g=this.syncGoal(), p=plan(raw,g.id,source); this.overlays.set(`${source}/${p.date}`,p);
            this.selected=p.date;this.month=monthOf(p.date);this.source=source;this.subject=null;this.error=''; ++this.detailSeq;
            if (refresh) this.cache.delete(`${g.id}/${this.month}`);
            await this.load(refresh);
        }
        plans(date) {
            const items=this.records.filter(p=>p.date===date);
            for (const p of this.overlays.values()) if (p.date === date && p.raw.goalId === this.goal?.id) {const i=items.findIndex(s=>s.source===p.source);if(i>=0)items.splice(i,1);items.push(p);}
            return items;
        }
        render() {
            const focus=this.root.contains(document.activeElement) ? document.activeElement.dataset.pcFocus : null;
            const header=el('div','pc-calendar-header'), title=el('div');title.append(el('h2','','학습 일정'),el('p','pc-goal',this.goal?.name || '목표를 선택하면 저장된 일정을 표시합니다.'));
            const controls=el('div','pc-controls');
            const prev=button('‹',()=>this.move(-1)), next=button('›',()=>this.move(1));prev.setAttribute('aria-label','이전 달');next.setAttribute('aria-label','다음 달');prev.dataset.pcFocus='prev';next.dataset.pcFocus='next';prev.disabled=this.month==='1970-01';next.disabled=this.month==='2100-12';
            const current=el('strong','pc-month',this.month.replace('-','년 ')+'월'); current.setAttribute('aria-live','polite');
            const goToday=button('오늘',()=>{const date=today();this.selected=date;this.month=monthOf(date);this.source='saved';this.subject=null; ++this.detailSeq;this.load();});goToday.dataset.pcFocus='today';
            const refresh=button('저장본 새로고침',()=>this.load(true));refresh.dataset.pcFocus='refresh';
            controls.append(prev,current,next,goToday,refresh);header.append(title,controls);
            const status=el('p','pc-notice',this.busy?'월간 저장본을 조회하는 중…':this.error || '월간 저장본 · 날짜를 선택해 상세 시간표를 확인하세요.');status.setAttribute('role',this.error?'alert':'status');
            const layout=el('div','pc-calendar-layout'), left=el('div','pc-calendar-left'), grid=el('div','pc-calendar-grid');grid.setAttribute('role','group');grid.setAttribute('aria-label',this.month+' 월간 학습 일정');
            for (const name of ['월','화','수','목','금','토','일']) grid.append(el('span','pc-weekday',name));
            const first=dateOf(this.month+'-01'), start=new Date(+first), offset=(first.getUTCDay()+6)%7;start.setUTCDate(start.getUTCDate()-offset);
            const end=new Date(Date.UTC(first.getUTCFullYear(),first.getUTCMonth()+1,0));const count=Math.ceil((offset+end.getUTCDate())/7)*7;
            for (let i=0;i<count;i++) {
                const d=new Date(+start);d.setUTCDate(d.getUTCDate()+i);const date=dateKey(d),outside=monthOf(date)!==this.month;
                const all=this.plans(date), p=all.find(p=>p.source==='saved') || all[0], groups=p?subjects(p):[];
                const cell=button('',()=>this.select(date).catch(e=>{this.error=e.message;this.render();}),'pc-day'+(outside?' pc-outside':'')+(date===this.selected?' pc-selected':'')+(date===today()?' pc-today':''));cell.dataset.date=date;cell.dataset.pcFocus='day-'+date;
                cell.disabled=d.getUTCFullYear()<1970 || d.getUTCFullYear()>2100;
                cell.setAttribute('aria-pressed',String(date===this.selected));if(date===today())cell.setAttribute('aria-current','date');
                const hasPreview=all.some(p=>p.source!=='saved');const label=p?`${p.status} ${p.raw.studyMinutes}분 ${groups.map(g=>g.name).join(', ')}${hasPreview && p.source==='saved'?' · 미저장 미리보기도 있음':''}`:this.coverage&&!outside?'일정 없음':'미조회';
                const logs=this.logs(date),actual=this.measuredMinutes(logs);
                cell.setAttribute('aria-label',`${date} ${date===today()?'오늘 ':''}${label}${logs.length?` · 공부 기록 ${logs.length}건 ${actual===null?'시간 정보 없음':actual+'분'}`:''}`);
                const top=el('span','pc-day-top');top.append(el('span','',d.getUTCDate()));
                if (p) top.append(el('span','pc-state',p.source==='saved'?p.raw.planStatus:'미저장'));cell.append(top);
                if (p) {
                    cell.append(el('span','pc-day-total',`${p.raw.studyMinutes}분${hasPreview&&p.source==='saved'?' · ◇':''}`));
                    for (const g of groups.slice(0,2)) {const chip=el('span','pc-event');chip.style.setProperty('--subject-color',color(g.id));chip.append(el('span','pc-event-name',g.name),el('span','pc-event-time',g.measured?`${g.minutes}분`:'시간 정보 없음'));cell.append(chip);}
                    if(groups.length>2)cell.append(el('span','pc-more',`+${groups.length-2}개 과목`));
                }
                if(logs.length)cell.append(el('span','pc-record-marker',`● 기록 ${actual===null?'시간 미상':actual+'분'} · ${logs.length}건`));
                cell.addEventListener('keydown',e=>{const delta={ArrowLeft:-1,ArrowRight:1,ArrowUp:-7,ArrowDown:7}[e.key];if(delta){e.preventDefault();const target=shift(date,delta);try{dateOf(target);this.select(target);}catch(_){}}});
                grid.append(cell);
            }
            const legend=el('div','pc-legend');const seen=new Set();for(const p of [...this.records,...this.overlays.values()])for(const g of subjects(p)){if(seen.has(g.key))continue;seen.add(g.key);const item=el('span','pc-legend-item',g.name);item.style.setProperty('--subject-color',color(g.id));legend.append(item);}
            left.append(grid,legend,el('p','pc-hint','FINAL / DRAFT는 서버 저장 상태 · ◇ 미저장 계산은 명시 조회 시에만 표시합니다. 인접 월은 조회 전까지 미조회입니다.'));
            const totals=el('div','pc-month-summary');totals.setAttribute('aria-label','월간 학습 요약');
            const confirmed=this.records.filter(p=>p.source==='saved' && p.raw.planStatus==='FINAL'), monthLogs=this.logRecords.filter(r=>r.studyDate.startsWith(this.month+'-')), actualTotal=this.measuredMinutes(monthLogs);
            for(const [label,value] of [['확정 계획',this.coverage?(confirmed.length?confirmed.reduce((sum,p)=>sum+p.raw.studyMinutes,0)+'분':'등록된 계획 없음'):'미조회'],['실제 공부',this.logCoverage?(actualTotal===null?'시간 정보 없음':actualTotal+'분'):'미조회'],['공부 기록',this.logCoverage?monthLogs.length+'건':'미조회']]){const item=el('div');item.append(el('span','',label),el('strong','',value));totals.append(item);}
            layout.append(left,this.detail());this.root.replaceChildren(header,totals,status);
            if(this.logError){const warning=el('p','pc-notice pc-record-notice','공부 기록 조회 실패 · '+this.logError);warning.setAttribute('role','alert');this.root.append(warning);}
            this.root.append(layout);
            if(focus){const node=[...this.root.querySelectorAll('[data-pc-focus]')].find(n=>n.dataset.pcFocus===focus);node?.focus({preventScroll:true});}
        }
        detail() {
            const panel=el('section','pc-day-detail');panel.setAttribute('aria-label','선택 날짜 상세 일정');panel.append(el('h3','',this.selected),el('p','pc-goal',this.goal?.name || '목표 미선택'));
            const all=this.plans(this.selected), selected=all.find(p=>p.source===this.source) || all.find(p=>p.source==='saved') || all[0];
            if (!selected) {panel.append(el('p','pc-empty',this.busy?'조회 중…':this.coverage && monthOf(this.selected)===this.month || this.knownEmpty.has(this.selected)?'이 날짜에 저장된 일정이 없습니다.':'이 날짜는 아직 조회되지 않았습니다. 날짜를 선택해 저장본을 확인하세요.'),this.actual(this.selected,null));return panel;}
            const sources=el('div','pc-sources');for(const p of all){const b=button(p.status,()=>{this.source=p.source;this.subject=null;this.render();},'pc-source');b.setAttribute('aria-pressed',String(p===selected));b.dataset.pcFocus='source-'+p.source;sources.append(b);}panel.append(sources);
            panel.append(el('p','pc-source-notice',selected.source==='saved'?'서버에 저장된 스냅샷입니다.':'저장되지 않은 계산 결과입니다. 달력에서 확인해도 자동 저장되지 않습니다.'));
            const stats=el('dl','pc-day-stats');for(const [key,label] of [['studyMinutes','전체 공부'],['carryoverMinutes','보충 배정'],['newStudyMinutes','신규 학습'],['carryoverTotalPendingMinutes','전체 미완료']]){if(typeof selected.raw[key]!=='number')continue;const item=el('div');item.append(el('dt','',label),el('dd','',selected.raw[key]+'분'));stats.append(item);}panel.append(stats);
            panel.append(el('p','pc-hint','미완료 전체와 실제 보충 배정은 다릅니다. 배정은 공부 완료 기록이 아닙니다.'));
            if (selected.raw.warning) panel.append(el('p','pc-notice',selected.raw.warning));
            const filters=el('div','pc-subject-filters');const addFilter=(key,label,id)=>{const b=button(label,()=>{this.subject=key;this.render();});b.style.setProperty('--subject-color',color(id));b.setAttribute('aria-pressed',String(this.subject===key));b.dataset.pcFocus='subject-'+key;filters.append(b);};addFilter(null,'전체 구간');for(const g of subjects(selected))addFilter(g.key,`${g.name} · ${g.measured?g.minutes+'분':'시간 정보 없음'}`,g.id);panel.append(filters);
            if (this.subject)panel.append(el('p','pc-hint','선택 과목의 구간 · 휴식 포함'));
            const timeline=el('div','pc-detail-timeline');
            if(!selected.slots.length)timeline.append(el('p','pc-empty','저장된 시간표 세부 정보가 없습니다. 임의 시간표를 만들지 않습니다.'));
            for(const s of selected.slots){const rest=s.type==='휴식',busy=s.type==='다른 일정';const key=Number.isSafeInteger(s.subjectId)&&s.subjectId>0?'id:'+s.subjectId:'unknown:'+(s.subjectName || '과목명 정보 없음');if(this.subject && this.subject!==key && !rest)continue;
                const row=el('details','pc-slot'+(rest?' pc-rest':''));row.style.setProperty('--subject-color',rest||busy?'#8b95a5':color(s.subjectId));
                const summary=el('summary');const label=rest?'휴식':busy?'다른 일정':s.subjectName || '과목명 정보 없음';const allocation=rest?'휴식':s.allocationType==='CATCHUP'?'↻ 보충 학습':s.allocationType==='NEW'?'▤ 신규 학습':s.type || '배정 구분 정보 없음';
                summary.append(el('span','pc-slot-clock',`${clock(s.startTime)}–${clock(s.endTime)}`),el('strong','',label),el('span','pc-slot-tag',allocation),el('span','pc-slot-minutes',typeof s.minutes==='number'?`${s.minutes}분`:'시간 정보 없음'));
                if(s.activity)summary.append(el('span','pc-slot-activity',s.activity));row.append(summary,el('p','pc-hint',`${this.selected} · ${this.goal?.name || ''} · ${selected.status}`));timeline.append(row);
            }
            panel.append(timeline,this.actual(this.selected,selected));return panel;
        }
    }
    const root=document.getElementById('studyPlanCalendar');const view=root?new CalendarView(root):null;
    window.StudyPlanCalendar=Object.freeze({CalendarView,parsePlan:plan,subjectColor:color,today,load:force=>view?.load(force),showPreview:data=>view?.show(data,'preview'),showSaved:(data,refresh=false)=>view?.show(data,'saved',refresh),showCalculated:data=>view?.show(data,data.planSaved===true?'saved':'calculated')});
})();
