{{flutter_js}}
{{flutter_build_config}}
(async function () {
  if (await window.PlannerPwaStart()===false) return;
  _flutter.loader.load({config:{
    canvasKitBaseUrl:new URL('canvaskit/',document.baseURI).toString(),
    fontFallbackBaseUrl:new URL('assets/fonts/',document.baseURI).toString()
  }});
})();

