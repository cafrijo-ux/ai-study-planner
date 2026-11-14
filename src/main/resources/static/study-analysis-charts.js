/* AI 공부 플래너 - 학습 분석 그래프 (조회 전용, 2026-10-21)
 * 기존 dashboard.html과 API를 재사용합니다. POST/PUT/DELETE, AI 요청을 사용하지 않습니다.
 */
(function () {
  'use strict';
  const DAYS = 7;
  let requestId = 0;
  let root;
  const id = (a,b) => String(a) === String(b);
  const validScore = value => value !== null && value !== undefined && value !== '' && Number.isInteger(Number(value)) && Number(value) >= 1 && Number(value) <= 5;
  const validMinutes = value => value !== null && value !== undefined && value !== '' && Number.isInteger(Number(value)) && Number(value) >= 0;
  const validDay = value => /^\d{4}-\d{2}-\d{2}$/.test(String(value || '').slice(0,10)) ? String(value).slice(0,10) : null;
  const fmt = minutes => Number(minutes).toLocaleString('ko-KR') + '분';
  function el(tag, className, content) {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (content !== undefined) item.textContent = String(content);
    return item;
  }
  function dateKey(date) {
    const pad = n => String(n).padStart(2, '0');
    return date.getFullYear() + '-' + pad(date.getMonth()+1) + '-' + pad(date.getDate());
  }
  function recentDates() {
    const parts = new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
    const part = type => parts.find(p=>p.type===type).value;
    const today = Date.UTC(Number(part('year')), Number(part('month'))-1, Number(part('day')));
    return Array.from({length:DAYS}, (_,i) => new Date(today - (DAYS-i-1)*86400000).toISOString().slice(0,10));
  }
  function panel(title, explanation) {
    const p = el('section', 'sac-panel');
    p.append(el('h3', '', title), el('p','sac-note',explanation));
    return p;
  }
  function putMessage(parent, content) { parent.appendChild(el('p','sac-empty',content)); }
  function addStyles() {
    if (document.getElementById('sac-styles')) return;
    const style = document.createElement('style');
    style.id = 'sac-styles';
    style.textContent = `
      .sac-card h2{margin-bottom:8px}.sac-note{color:#64748b;font-size:13px;line-height:1.55;margin:5px 0 16px}
      .sac-panels{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}
      .sac-panel{min-width:0;background:#fbfdff;border:1px solid #e2e8f0;border-radius:12px;padding:17px}
      .sac-panel h3{font-size:16px;margin:0;color:#1e293b}
      .sac-panel.sac-wide{grid-column:1/-1}.sac-rows{display:grid;gap:10px}
      .sac-row{display:grid;grid-template-columns:90px minmax(0,1fr) 82px;gap:10px;align-items:center;font-size:13px}
      .sac-name{min-width:0;overflow-wrap:anywhere;color:#334155}.sac-value{text-align:right;font-variant-numeric:tabular-nums;color:#334155}
      .sac-track{height:13px;background:#e2e8f0;border-radius:20px;overflow:hidden}
      .sac-fill{height:100%;background:#2563eb;border-radius:20px;min-width:0}
      .sac-fill.sac-alt{background:#0891b2}.sac-subject .sac-fill{background:#2563eb}
      .sac-score-row{display:grid;grid-template-columns:88px minmax(0,1fr);gap:12px;align-items:start;font-size:13px}
      .sac-score-line{display:grid;grid-template-columns:64px minmax(0,1fr) 48px;gap:7px;align-items:center;margin-bottom:5px;font-size:12px}
      .sac-score-line .sac-track{height:8px}.sac-empty{padding:15px;background:#f1f5f9;border-radius:8px;color:#475569;font-size:13px;line-height:1.6}
      .sac-legend{font-size:12px;color:#64748b;margin-top:12px}
      @media(max-width:820px){.sac-panels{grid-template-columns:1fr}.sac-panel.sac-wide{grid-column:auto}}
      @media(max-width:400px){.sac-panel{padding:12px}.sac-row{grid-template-columns:72px minmax(0,1fr) 68px;gap:5px;font-size:12px}}
    `;
    document.head.appendChild(style);
  }
  function ensureCard() {
    if (root) return root;
    const analysisCards = document.querySelectorAll('.card[data-section="analysis"]');
    if (!analysisCards.length) return null;
    addStyles();
    root = el('div','card sac-card');
    root.dataset.section = 'analysis';
    root.appendChild(el('h2','', '학습 기록 시각화'));
    root.appendChild(el('p','sac-note','현재 선택한 학습 목표의 실제 공부 기록만 사용합니다. 계획 시간과 미입력 날짜는 공부 완료로 계산하지 않습니다.'));
    const content=el('div','sac-panels');content.id='sac-content';root.appendChild(content);
    const anchor = analysisCards[1] || analysisCards[0].nextSibling;
    if (analysisCards[1]) analysisCards[1].parentNode.insertBefore(root,analysisCards[1]);
    else analysisCards[0].parentNode.insertBefore(root,analysisCards[0].nextSibling);
    // 탭 초기화가 끝난 뒤 추가되어도 현재 선택 상태를 존중합니다.
    root.classList.toggle('is-visible',document.querySelector('.dashboard-nav [data-tab="analysis"]')?.getAttribute('aria-pressed') === 'true');
    return root;
  }
  function target() { return document.getElementById('sac-content'); }
  function barRow(label, minutes, max, emptyNote, alternate) {
    const row=el('div','sac-row');
    row.appendChild(el('span','sac-name',label));
    const track=el('div','sac-track');
    const fill=el('div','sac-fill'+(alternate?' sac-alt':''));
    fill.style.width=(max>0?Math.min(100,minutes/max*100):0)+'%';
    track.appendChild(fill);row.appendChild(track);
    row.appendChild(el('span','sac-value',emptyNote || fmt(minutes)));
    return row;
  }
  function draw(logs, subjects, currentGoal) {
    const content=target(); if(!content) return;
    content.replaceChildren();
    const ownSubjects=subjects.filter(s => id(s.goalId,currentGoal) && id(s.userId,1));
    const subjectMap=new Map(ownSubjects.map(s=>[String(s.subjectId),String(s.subjectName || '과목 '+s.subjectId)]));
    const ownLogs=logs.filter(log=>id(log.userId,1) && subjectMap.has(String(log.subjectId)));
    const dates=recentDates();
    const byDate=new Map(dates.map(d=>[d,[]]));
    ownLogs.forEach(log=>{const key=validDay(log.studyDate);if(byDate.has(key)) byDate.get(key).push(log);});

    const daily=panel('최근 7일 공부시간','기록이 없는 날짜는 0분 공부로 확정하지 않습니다.');
    const dailyRows=el('div','sac-rows');
    const dailyTimes=dates.map(d=>({date:d, logs:byDate.get(d), minutes:byDate.get(d).filter(l=>validMinutes(l.studyMinutes)).reduce((sum,l)=>sum+Number(l.studyMinutes),0)}));
    const maxDaily=Math.max(1,...dailyTimes.map(d=>d.minutes));
    dailyTimes.forEach(d=>dailyRows.appendChild(barRow(d.date.slice(5),d.minutes,maxDaily,d.logs.length===0?'기록 없음':d.logs.some(l=>validMinutes(l.studyMinutes))?null:'시간 미입력')));
    daily.appendChild(dailyRows);
    daily.appendChild(el('p','sac-legend','최근 7일 실제 기록 '+dailyTimes.reduce((n,d)=>n+d.logs.length,0)+'건 · 기록된 공부시간 '+fmt(dailyTimes.reduce((n,d)=>n+d.minutes,0))));
    content.appendChild(daily);

    const subject=panel('과목별 공부시간 비율','현재 목표의 누적 실제 학습시간 기준입니다.');
    const minutesBySubject=ownSubjects.map(s=>({name:subjectMap.get(String(s.subjectId)),minutes:ownLogs.filter(log=>id(log.subjectId,s.subjectId) && validMinutes(log.studyMinutes)).reduce((n,l)=>n+Number(l.studyMinutes),0)})).sort((a,b)=>b.minutes-a.minutes);
    const total=minutesBySubject.reduce((n,s)=>n+s.minutes,0);
    if(!ownSubjects.length) putMessage(subject,'현재 목표에 등록된 과목이 없습니다.');
    else if(!total) putMessage(subject,'계산 가능한 공부시간 기록이 없습니다.');
    else {
      const rows=el('div','sac-rows');
      minutesBySubject.forEach(s=>rows.appendChild(barRow(s.name,s.minutes,total,(s.minutes/total*100).toFixed(1)+'%',true)));
      subject.appendChild(rows);
      subject.appendChild(el('p','sac-legend','전체 목표 누적 '+fmt(total)+' · 과목별 시간 비율입니다.'));
    }
    content.appendChild(subject);

    const scores=panel('최근 7일 이해도 · 집중도','기록된 점수만 평균을 계산합니다. 점수가 없는 날은 표시하지 않습니다.');scores.classList.add('sac-wide');
    const scoreRows=el('div','sac-rows');
    let withAnyScore=0;
    for (const d of dailyTimes) {
      const scored={understanding:d.logs.filter(l=>validScore(l.understandingScore)).map(l=>Number(l.understandingScore)),focus:d.logs.filter(l=>validScore(l.focusScore)).map(l=>Number(l.focusScore))};
      if (!scored.understanding.length && !scored.focus.length) continue;
      withAnyScore++;
      const r=el('div','sac-score-row');r.appendChild(el('span','sac-name',d.date.slice(5)));
      const pair=el('div');
      [['이해도',scored.understanding,false],['집중도',scored.focus,true]].forEach(([label,values,alt])=>{
        const line=el('div','sac-score-line');line.appendChild(el('span','',label));
        const t=el('div','sac-track'),f=el('div','sac-fill'+(alt?' sac-alt':''));
        const avg=values.length?values.reduce((x,y)=>x+y,0)/values.length:null;
        f.style.width=avg!==null?avg/5*100+'%':'0%';t.appendChild(f);line.appendChild(t);
        line.appendChild(el('span','sac-value',avg!==null?avg.toFixed(1)+'/5':'정보 없음'));
        pair.appendChild(line);
      });
      r.appendChild(pair);scoreRows.appendChild(r);
    }
    if(!withAnyScore) putMessage(scores,'최근 7일 동안 유효한 이해도·집중도 점수 기록이 없습니다.');
    else scores.appendChild(scoreRows);
    scores.appendChild(el('p','sac-legend','파란색: 이해도 · 청록색: 집중도 · 기준 5점 만점'));
    content.appendChild(scores);
  }
  async function getList(path) {
    const response=await fetch(path,{cache:'no-store',credentials:'same-origin'});
    if(!response.ok) throw new Error(path+' 조회 실패 (HTTP '+response.status+')');
    const result=await response.json();
    if(!Array.isArray(result)) throw new Error(path+'가 목록 형식으로 응답하지 않았습니다.');
    return result;
  }
  async function load() {
    if(!ensureCard()) return;
    const key=String(window.GoalContext?.id || new URLSearchParams(location.search).get('goalId') || '');
    const seq=++requestId;
    const content=target();
    content.replaceChildren(el('p','sac-empty','현재 목표의 공부 기록을 분석하는 중...'));
    if(!/^\d+$/.test(key) || Number(key)<1) {
      content.replaceChildren(el('p','sac-empty','현재 학습 목표를 먼저 선택해 주세요.'));return;
    }
    try {
      const [subjects, logs] = await Promise.all([getList('/subjects'),getList('/study-logs')]);
      if(seq!==requestId || key!==String(window.GoalContext?.id || new URLSearchParams(location.search).get('goalId') || '')) return;
      draw(logs,subjects,key);
    } catch (error) {
      if(seq!==requestId) return;
      content.replaceChildren(el('p','sac-empty','학습 분석을 불러오지 못했습니다. 로그인 상태와 서버 연결을 확인해 주세요. ('+(error.message||String(error))+')'));
    }
  }
  window.StudyAnalysisCharts={load};
  ensureCard();
})();
