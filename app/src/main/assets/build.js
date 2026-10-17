(function(){
var PROMPT=RAIN_PROMPT_TOKEN;
var TIMEOUTMS=120000;
var IDLEDONEMS=4000;

function isVisible(e){
if(!e) return false;
var style = window.getComputedStyle(e);
if(style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
return true;
}

function findInput(){
var tas = document.querySelectorAll('textarea');
for(var i=0;i<tas.length;i++){ if(isVisible(tas[i])) return {el:tas[i],type:'textarea'}; }
var ces = document.querySelectorAll('div[contenteditable]');
for(var j=0;j<ces.length;j++){ if(isVisible(ces[j])) return {el:ces[j],type:'contenteditable'}; }
return null;
}

function scoreSendCandidate(e, inputEl){
var score = 0;
var cls = (e.className||'') + ' ';
var txt = (e.innerText||e.getAttribute('aria-label')||'').toLowerCase();
if(cls.indexOf('ds-button--circle')>=0) score += 100;
if(cls.indexOf('circle')>=0) score += 50;
if(cls.indexOf('primary')>=0) score += 30;
if(cls.indexOf('send')>=0) score += 80;
if(txt.indexOf('send')>=0 || txt.indexOf('发送')>=0) score += 80;
var dis = cls.indexOf('disabled')>=0 || e.disabled;
if(dis) score -= 200;
if(inputEl){
var ir = inputEl.getBoundingClientRect();
var er = e.getBoundingClientRect();
var dx = Math.abs(ir.right - er.left);
var dy = Math.abs((ir.top+ir.bottom)/2 - (er.top+er.bottom)/2);
if(dy < 60) score += 40;
if(dx < 100) score += 20;
}
return score;
}

function findSendBtn(inputEl){
var exact = document.querySelector('div.ds-button--circle:not(.ds-button--disabled)');
if(exact && isVisible(exact)) return exact;
var cands = document.querySelectorAll('button, div[role="button"], div[class*=button], div[class*=btn]');
var best = null, bestScore = 0;
for(var i=0;i<cands.length;i++){
var c = cands[i];
if(!isVisible(c)) continue;
var s = scoreSendCandidate(c, inputEl);
if(s > bestScore){ bestScore = s; best = c; }
}
return bestScore >= 40 ? best : null;
}

function collectAnswerContainers(){
var out = [];
var mds = document.querySelectorAll('div[class*=ds-markdown], div[class*=markdown-body], div[class*=markdown]');
for(var i=0;i<mds.length;i++){
if(isVisible(mds[i])) out.push(mds[i]);
}
return out;
}

function extractThoughtAndText(){
 var thinks = document.querySelectorAll('div[class*=think]');
 var thought = '';
 for(var i=0; i<thinks.length; i++){
 if(isVisible(thinks[i])) {
 thought = thinks[i].innerText;
 break;
 }
 }
 var text = lastMarkdownText();
 return {thought: thought, text: text};
}
function countMarkdown(){ return collectAnswerContainers().length; }

function lastMarkdownText(){
var cs = collectAnswerContainers();
if(cs.length > 0) return cs[cs.length-1].innerText;
return '';
}

function inputEmptied(el){
try{
if(el.tagName==='TEXTAREA'||el.tagName==='INPUT') return (el.value||'').trim()==='';
return (el.textContent||'').trim()==='';
}catch(e){ return false; }
}

var inp = findInput();
if(!inp){ RainBridge.onError('input not found'); return; }
var el = inp.el;

if(inp.type === 'textarea'){
var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;
setter.call(el, PROMPT);
} else {
el.focus();
el.innerText = PROMPT;
}

el.dispatchEvent(new Event('input',{bubbles:true}));
el.dispatchEvent(new Event('change',{bubbles:true}));
el.dispatchEvent(new Event('keyup',{bubbles:true}));

var baseline = countMarkdown();
var startTs = Date.now();
var btnTries = 0;
var sent = false;

function startObserve(){
var lastText = '';
var lastChangeTs = Date.now();
var started = false;
var finalized = false;
function finish(text){
if(finalized) return;
finalized = true;
try{ mo.disconnect(); }catch(e){}
try{ clearInterval(iv); }catch(e){}
var resObj = extractThoughtAndText();
 if (resObj.thought) {
 RainBridge.onResult('T_SEP' + resObj.thought + 'C_SEP' + resObj.text);
 } else {
 RainBridge.onResult(resObj.text || '（空回复）');
 }
}
var mo = new MutationObserver(function(){
if(finalized) return;
var n = countMarkdown();
if(!started && n > baseline) started = true;
if(!started) return;
var t = lastMarkdownText();
if(t && t !== lastText){ lastText = t; lastChangeTs = Date.now(); }
});
try{ mo.observe(document.body,{childList:true,subtree:true,characterData:true}); }catch(e){}
var iv = setInterval(function(){
if(finalized) return;
if(Date.now()-startTs > TIMEOUTMS){
clearInterval(iv);
try{ mo.disconnect(); }catch(e){}
RainBridge.onError('reply timeout');
return;
}
var n = countMarkdown();
if(!started && n > baseline) started = true;
if(!started) return;
var t = lastMarkdownText();
if(t && t !== lastText){ lastText = t; lastChangeTs = Date.now(); }
if(lastText && Date.now()-lastChangeTs > IDLEDONEMS){ finish(lastText); }
}, 300);
}

var ivBtn = setInterval(function(){
btnTries++;
if(Date.now()-startTs > TIMEOUTMS){
clearInterval(ivBtn);
RainBridge.onError('send btn timeout');
return;
}

if(!sent){
var btn = findSendBtn(el);
i'''
 el.focus();
 var rect = btn.getBoundingClientRect();
 var cx = rect.left + rect.width / 2;
 var cy = rect.top + rect.height / 2;
 var down = new MouseEvent('mousedown', {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy});
 btn.dispatchEvent(down);
 var up = new MouseEvent('mouseup', {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy});
 btn.dispatchEvent(up);
 btn.click();
''' catch(e){ RainBridge.onError('click fail: ' + e.message); return; }sent = true; // 点击后等待输入框清空
}
} else {
// 等待输入框被清空（消息成功发出）
if(inputEmptied(el) || Date.now() - startTs > 10000){ // 10s 强制兜底
clearInterval(ivBtn);
startObserve();
}
}
}, 200);
})();