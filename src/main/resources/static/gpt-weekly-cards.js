/* AI 공부 플래너 — GPT 주간 코칭 미리보기 요약 카드.
 * 기존 GET /gpt-weekly-coaching/preview 응답만 표시합니다.
 * 별도의 네트워크 요청, 생성/저장/DB 수정, GPT API 호출을 하지 않습니다.
 */
(function () {
    'use strict';

    const PREFIX = 'gwc-';
    let main = null;

    function element(tag, className, content) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (content !== undefined) node.textContent = String(content);
        return node;
    }

    function known(value) {
        return value !== null && value !== undefined && String(value).trim() !== '';
    }

    function integer(value, min = 0) {
        if (!known(value) || !Number.isSafeInteger(Number(value)) || Number(value) < min) return null;
        return Number(value);
    }

    function number(value, min = 0, max = 5) {
        if (!known(value) || !Number.isFinite(Number(value)) || Number(value) < min || Number(value) > max) return null;
        return Number(value);
    }

    function minutes(value) {
        const n = integer(value);
        return n === null ? '정보 없음' : n.toLocaleString('ko-KR') + '분';
    }

    function score(value) {
        const n = number(value);
        return n === null ? '기록 없음' : (Math.round(n * 10) / 10).toLocaleString('ko-KR') + ' / 5';
    }

    function labeledValue(label, value, className = '') {
        const wrapper = element('div', 'gwc-stat ' + className);
        wrapper.append(element('span', 'gwc-stat-label', label), element('strong', 'gwc-stat-value', value));
        return wrapper;
    }

    function section(title, description) {
        const box = element('section', 'gwc-block');
        box.append(element('h4', '', title), element('p', 'gwc-hint', description));
        return box;
    }

    function styleOnce() {
        if (document.getElementById('gwc-styles')) return;
        const style = document.createElement('style');
        style.id = 'gwc-styles';
        style.textContent = `
          .gwc-summary{margin:16px 0;padding:15px;background:#fff;border:1px solid #dbe5f0;border-radius:12px;min-width:0}
          .gwc-summary>h3{color:#1e3a8a;font-size:17px;margin:0 0 5px}
          .gwc-intro{color:#64748b;font-size:13px;line-height:1.55;margin:0 0 13px}
          .gwc-panels{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
          .gwc-block{padding:14px;border-radius:10px;border:1px solid #e2e8f0;background:#f8fafc;min-width:0}
          .gwc-block h4{font-size:15px;margin:0;color:#0f172a}
          .gwc-hint{font-size:12px;color:#64748b;margin:5px 0 10px;line-height:1.55}
          .gwc-stats{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
          .gwc-stat{min-width:0;display:flex;flex-direction:column;gap:6px;background:#fff;border:1px solid #e2e8f0;border-radius:8px;padding:11px}
          .gwc-stat-label{font-size:12px;color:#64748b}
          .gwc-stat-value{font-size:18px;color:#1d4ed8;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}
          .gwc-chips{display:flex;flex-wrap:wrap;gap:7px}
          .gwc-chip{font-size:13px;line-height:1.5;color:#1e40af;background:#dbeafe;border-radius:7px;padding:5px 9px;overflow-wrap:anywhere;max-width:100%}
          .gwc-line{font-size:13px;color:#334155;line-height:1.65;margin:5px 0;overflow-wrap:anywhere}
          .gwc-value{font-weight:700;color:#0f172a}
          .gwc-footnote{font-size:12px;line-height:1.55;color:#64748b;margin:10px 0 0}
          @media(max-width:720px){.gwc-panels{grid-template-columns:1fr}.gwc-summary{padding:11px}}
          @media(max-width:360px){.gwc-stat{padding:8px}.gwc-stat-value{font-size:16px}}
        `;
        document.head.appendChild(style);
    }

    function ensure() {
        const target = document.getElementById('gptWeeklyPanel');
        if (!target) return null;
        if (main && main.isConnected) return main;
        styleOnce();
        main = element('div', 'gwc-summary');
        main.id = 'gptWeeklySummaryCards';
        main.hidden = true;
        main.appendChild(element('h3', '', '이번 주 학습 한눈에 보기'));
        main.appendChild(element('p', 'gwc-intro', '서버에 기록된 사실을 요약합니다. 아래 AI 코칭 결과와는 별도이며 미리보기에는 GPT 호출이 없습니다.'));
        main.appendChild(element('div', 'gwc-panels'));
        const metrics = document.getElementById('gptWeeklyMetrics');
        const metricsBox = metrics && metrics.closest('.coaching-box');
        if (metricsBox && metricsBox.parentNode === target) target.insertBefore(main, metricsBox);
        else target.appendChild(main);
        return main;
    }

    function reset() {
        const box = ensure();
        if (!box) return;
        box.hidden = true;
        const panels = box.querySelector('.gwc-panels');
        if (panels) panels.replaceChildren();
    }

    function line(box, name, text) {
        const p = element('p', 'gwc-line');
        p.append(element('span', '', name + ' '), element('span', 'gwc-value', text));
        box.appendChild(p);
    }

    function render(data) {
        const box = ensure();
        if (!box) return;
        reset();
        if (!data || typeof data !== 'object' || Array.isArray(data)) return;
        const panels = box.querySelector('.gwc-panels');

        // 실제 학습 기록: 계획 시간과 절대 합산하지 않음.
        const activity = section('① 실제 공부 기록',
            (known(data.periodStart) && known(data.periodEnd))
                ? data.periodStart + ' ~ ' + data.periodEnd : '분석 기간 정보 없음');
        const activityStats = element('div', 'gwc-stats');
        const count = integer(data.recordCount);
        activityStats.append(
            labeledValue('기록 건수', count === null ? '정보 없음' : count + '건'),
            labeledValue('기록된 공부시간', count === 0 ? '기록 없음' : minutes(data.totalStudyMinutes)),
            labeledValue('평균 이해도', score(data.averageUnderstanding)),
            labeledValue('평균 집중도', score(data.averageFocus))
        );
        activity.appendChild(activityStats);
        activity.appendChild(element('p', 'gwc-footnote', '공부 기록이 없는 날짜를 실제로 공부하지 않은 날로 단정하지 않습니다.')); 
        const rate = number(data.correctRate, 0, 100);
        if (rate !== null) line(activity, '기록 기반 정답률:', rate.toLocaleString('ko-KR') + '%');
        panels.appendChild(activity);

        const weak = section('② 어려웠던 개념', '공부 기록에 사용자가 입력한 개념입니다. AI가 새롭게 추론한 약점은 아닙니다.');
        const topics = Array.isArray(data.weakTopics)
            ? data.weakTopics.filter(value => typeof value === 'string' && value.trim()).slice(0, 12) : [];
        if (topics.length) {
            const chips = element('div', 'gwc-chips');
            topics.forEach(topic => chips.appendChild(element('span', 'gwc-chip', topic.trim())));
            weak.appendChild(chips);
        } else {
            weak.appendChild(element('p', 'gwc-line', '입력된 어려운 개념 정보가 없습니다.'));
        }
        panels.appendChild(weak);

        const planned = section('③ 저장된 학습 계획', '계획 시간과 실제 공부 완료 시간은 다릅니다.');
        if (data.savedPlanReferenced === true) {
            line(planned, '오늘·내일 계획:',
                [data.savedPlanDate, data.savedPlanStatus].filter(known).join(' · ') || '저장된 계획');
            line(planned, '계획 총시간:', minutes(data.savedPlanStudyMinutes));
            line(planned, '보충 학습 배정:', minutes(data.savedPlanCarryoverMinutes));
            line(planned, '신규 학습 배정:', minutes(data.savedPlanNewStudyMinutes));
        } else {
            planned.appendChild(element('p', 'gwc-line', '오늘·내일 저장된 계획이 없습니다.'));
        }
        const comp = data.weeklyPlanComparison;
        if (comp && typeof comp === 'object' && !Array.isArray(comp)) {
            const finalDays = integer(comp.finalPlanDays);
            line(planned, '최근 7일 FINAL 계획:',
                (finalDays === null ? '정보 없음' : finalDays + '일') + ' · 계획 합계 ' + minutes(comp.knownFinalPlannedMinutes));
        }
        planned.appendChild(element('p', 'gwc-footnote', '과거 FINAL은 계획 이력입니다. 미래의 확정 일정이나 실제 완료 시간으로 해석하지 않습니다.'));
        panels.appendChild(planned);

        const guidance = section('④ AI 추천 학습 전략', '실제 GPT 생성 버튼으로 호출한 결과만 아래 「GPT 코칭 결과」 영역에 표시합니다.');
        guidance.appendChild(element('p', 'gwc-line',
            data.responseSource === 'provider' && typeof data.llmResponse === 'string' && data.llmResponse.trim()
                ? '아래 GPT 코칭 결과는 API에서 받은 권고입니다. 저장된 FINAL 계획을 자동으로 변경하지 않습니다.'
                : '현재 화면은 저장 데이터 미리보기입니다. GPT가 아직 답하지 않았다면 AI 추천 전략도 생성되지 않았습니다.'));
        panels.appendChild(guidance);
        box.hidden = false;
    }

    window.GptWeeklyCards = Object.freeze({ render, reset });
})();
