/* AI 공부 플래너 - 어려웠던 개념별 복습 우선순위 (조회 전용)
 * 현재 선택 목표의 최근 30일 공부 기록을 조회합니다.
 * 규칙 기반 참고용 분석이며 GPT 응답, 미완료 학습량 또는 저장된 FINAL 계획이 아닙니다.
 */
(function () {
  'use strict';

  let sequence = 0;
  let panel = null;
  const LOOKBACK_DAYS = 30;
  const MAX_RESULTS = 5;
  const same = (left, right) => String(left) === String(right);
  const node = (tag, className, text) => {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text !== undefined) item.textContent = String(text);
    return item;
  };
  const intScore = v => v !== null && v !== undefined && v !== '' && Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 5 ? Number(v) : null;
  const avg = vals => vals.length ? vals.reduce((x,y)=>x+y,0)/vals.length : null;
  const scoreText = x => x === null ? '정보 없음' : x.toFixed(1) + '/5';
  const idPattern = /^\d+$/;
  const pad = x => String(x).padStart(2, '0');
  const dateKey = d => d.getFullYear() + '-' + pad(d.getMonth()+1) + '-' + pad(d.getDate());
  function range() {
    const parts = new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
    const part = type => parts.find(p=>p.type===type).value;
    const today = Date.UTC(Number(part('year')), Number(part('month'))-1, Number(part('day')));
    const daysAgo = days => new Date(today-days*86400000).toISOString().slice(0,10);
    return { from: daysAgo(LOOKBACK_DAYS-1), through: daysAgo(0), recent: daysAgo(6) };
  }
  function validDay(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}(?:$|T)/.test(value)) return null;
    const key = value.slice(0, 10);
    const d = new Date(key + 'T12:00:00');
    return !Number.isNaN(d.getTime()) && dateKey(d) === key ? key : null;
  }
  function terms(value) {
    if (typeof value !== 'string') return [];
    const seen = new Set();
    return value.split(/[,;，；\n]+/).map(s=>s.trim().replace(/\s+/g, ' ')).filter(t=>{
      if (!t || t.length>110 || /^(없음|모름|해당 없음|해당없음|없다|[-–—])$/i.test(t)) return false;
      const key = t.toLocaleLowerCase('ko-KR');
      if (seen.has(key)) return false;
      seen.add(key);return true;
    }).slice(0, 12);
  }
  function rank(subjects, logs, goal, dates) {
    const scoped = subjects.filter(s => s && same(s.userId, 1) && same(s.goalId, goal));
    const names = new Map(scoped.map(s => [String(s.subjectId), String(s.subjectName || '과목 '+s.subjectId)]));
    const candidates = new Map();
    let count = 0;
    for (const log of logs) {
      if (!log || !same(log.userId, 1) || !names.has(String(log.subjectId))) continue;
      const day = validDay(log.studyDate);
      if (!day || day < dates.from || day > dates.through) continue;
      count++;
      for (const term of terms(log.difficultTopic)) {
        const key = String(log.subjectId) + ':' + term.toLocaleLowerCase('ko-KR');
        if (!candidates.has(key)) candidates.set(key, {subject:names.get(String(log.subjectId)), topic:term, times:0, understand:[], focus:[], latest:day});
        const item = candidates.get(key);
        item.times++;
        if (day > item.latest) item.latest=day;
        const u = intScore(log.understandingScore), f = intScore(log.focusScore);
        if (u !== null) item.understand.push(u);
        if (f !== null) item.focus.push(f);
      }
    }
    const sorted = [...candidates.values()].map(item=>{
      item.avgUnderstanding=avg(item.understand);
      item.avgFocus=avg(item.focus);
      const u=item.avgUnderstanding===null?0:Math.max(0,4-item.avgUnderstanding)*2;
      const f=item.avgFocus===null?0:Math.max(0,3-item.avgFocus);
      item.weight=Math.min(item.times,3)*3 + u + f + (item.latest>=dates.recent?2:0);
      if (item.avgUnderstanding!==null && item.avgUnderstanding<=2.5) item.strategy='관련 핵심 개념을 다시 정리한 후, 간단한 확인 문제로 이해도를 점검해 보세요.';
      else if (item.times>=2) item.strategy='같은 개념이 여러 번 어려웠다고 기록됐습니다. 예제를 다시 풀고 막히는 부분을 정리해 보세요.';
      else if (item.avgFocus!==null && item.avgFocus<=2.5) item.strategy='짧은 구간으로 나누어 복습하고, 집중이 흐트러지는 부분을 확인해 보세요.';
      else item.strategy='핵심 정의를 확인하고 한두 문제로 이해 여부를 점검해 보세요.';
      return item;
    }).sort((a,b)=>b.weight-a.weight || b.times-a.times || b.latest.localeCompare(a.latest) || a.topic.localeCompare(b.topic,'ko'));
    return {scopeSubjects:scoped.length, logCount:count, uniqueTopics:sorted.length, items:sorted.slice(0,MAX_RESULTS)};
  }
  function attachStyles() {
    if(document.getElementById('rpr-styles')) return;
    const style=node('style'); style.id='rpr-styles';style.textContent=`
      .rpr-card h2{margin-bottom:9px}.rpr-desc{font-size:13px;line-height:1.65;color:#64748b;margin:6px 0 15px}
      .rpr-meta{font-size:12px;color:#475569;margin:0 0 12px}
      .rpr-list{display:grid;gap:11px}.rpr-item{border:1px solid #dbe5f0;background:#f8fafc;border-radius:11px;padding:15px;min-width:0}
      .rpr-head{display:flex;gap:10px;align-items:flex-start}.rpr-index{flex:0 0 30px;width:30px;height:30px;display:grid;place-items:center;color:#1d4ed8;background:#dbeafe;border-radius:9px;font-size:13px;font-weight:800}
      .rpr-title{min-width:0;overflow-wrap:anywhere}.rpr-title h3{font-size:16px;margin:0 0 2px;color:#0f172a}.rpr-subject{font-size:12px;color:#64748b}
      .rpr-basis{font-size:13px;line-height:1.7;color:#334155;margin:11px 0 4px;overflow-wrap:anywhere}
      .rpr-advice{font-size:13px;line-height:1.65;color:#1e40af;margin:7px 0 0}
      .rpr-empty{font-size:13px;line-height:1.7;border:1px dashed #cbd5e1;border-radius:10px;padding:17px;background:#f8fafc;color:#475569}
      .rpr-refresh{margin-top:12px;padding:9px 13px;border:1px solid #cbd5e1;background:#eff6ff;border-radius:8px;color:#1d4ed8;cursor:pointer;font-size:13px;font-weight:700}
      @media(max-width:560px){.rpr-item{padding:12px}.rpr-card{min-width:0}.rpr-head{gap:8px}}
    `;document.head.appendChild(style);
  }
  function ensure() {
    if(panel && panel.isConnected) return panel;
    const target=document.getElementById('weakSubjectName')?.closest('.card[data-section="analysis"]');
    if(!target || !target.parentNode) return null;
    attachStyles();
    panel=node('section','card rpr-card');panel.dataset.section='analysis';panel.id='reviewPriorityPanel';
    panel.append(node('h2','', '어려웠던 개념 복습 우선순위'),node('p','rpr-desc','현재 목표의 최근 30일 기록을 바탕으로 복습 후보를 정리합니다. 기존 취약 과목 추천과 별개인 규칙 기반 참고 정보입니다.'));
    const message=node('div',''); message.id='rpr-content';message.setAttribute('aria-live','polite');panel.appendChild(message);
    const refresh=node('button','rpr-refresh','복습 후보 새로고침');refresh.type='button';refresh.addEventListener('click',load);panel.appendChild(refresh);
    panel.appendChild(node('p','rpr-desc','이 화면은 복습 후보를 조회하기만 하며, 저장된 학습 계획을 변경하거나 AI API를 호출하지 않습니다.'));
    target.parentNode.insertBefore(panel,target.nextSibling);
    panel.classList.toggle('is-visible',target.classList.contains('is-visible'));
    return panel;
  }
  function content() { return document.getElementById('rpr-content'); }
  function show(text) { const c=content();if(c)c.replaceChildren(node('p','rpr-empty',text)); }
  function render(data, dates) {
    const c=content();if(!c)return;
    c.replaceChildren(node('p','rpr-meta',dates.from+' ~ '+dates.through+' · 현재 목표 기록 '+data.logCount+'건 · 어려운 개념 '+data.uniqueTopics+'종'));
    if(!data.scopeSubjects) {c.appendChild(node('p','rpr-empty','현재 목표에 등록된 과목이 없습니다. 과목 등록 후 공부 기록을 입력해 주세요.'));return;}
    if(!data.logCount) {c.appendChild(node('p','rpr-empty','최근 30일 동안 확인되는 공부 기록이 없습니다. 공부를 하지 않았다는 뜻은 아닙니다.'));return;}
    if(!data.items.length) {c.appendChild(node('p','rpr-empty','최근 30일 기록에서 어려웠던 개념이 입력되지 않았습니다. 공부 기록에 어려웠던 개념을 남기면 복습 후보가 나타납니다.'));return;}
    const list=node('div','rpr-list');
    data.items.forEach((item,i)=>{
      const entry=node('article','rpr-item');const head=node('div','rpr-head');
      head.appendChild(node('span','rpr-index',i+1));const title=node('div','rpr-title');title.append(node('h3','',item.topic),node('span','rpr-subject',item.subject));head.appendChild(title);entry.appendChild(head);
      entry.appendChild(node('p','rpr-basis','어려운 개념 기록 '+item.times+'회 · 이해도 '+scoreText(item.avgUnderstanding)+' · 집중도 '+scoreText(item.avgFocus)+' · 최근 기록 '+item.latest));
      entry.appendChild(node('p','rpr-advice','복습 제안: '+item.strategy));list.appendChild(entry);
    });
    c.appendChild(list);
    c.appendChild(node('p','rpr-desc','표시 순서는 어려움 반복 횟수, 기록된 이해도·집중도, 최근 기록일을 참고합니다. 동일한 기록이 적거나 점수가 없으면 판단 근거가 제한적입니다.'));
  }
  async function getList(path) {
    const response = await fetch(path,{credentials:'same-origin',cache:'no-store'});
    if(!response.ok) throw new Error('HTTP '+response.status);
    const data=await response.json();
    if(!Array.isArray(data)) throw new Error('예상한 목록 응답이 아닙니다.');
    return data;
  }
  function goalId() { return String(window.GoalContext?.id || new URLSearchParams(location.search).get('goalId') || ''); }
  async function load() {
    if(!ensure()) return;
    const seq=++sequence, goal=goalId(), dates=range();
    if(!idPattern.test(goal) || Number(goal)<1){show('학습 목표를 먼저 선택해 주세요.');return;}
    show('복습 후보를 확인하는 중...');
    try {
      const [subjects,logs]=await Promise.all([getList('/subjects'),getList('/study-logs')]);
      if(seq!==sequence || goal!==goalId()) return;
      render(rank(subjects,logs,goal,dates),dates);
    } catch(err) {
      if(seq===sequence) show('복습 정보를 불러오지 못했습니다. 로그인 상태나 서버 응답을 확인해 주세요. ('+(err.message||String(err))+')');
    }
  }
  window.ReviewPriority=Object.freeze({load});
  ensure();
})();
