(function(){
// 1. 基础反检测与后台保活
try {
Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
window.chrome = { runtime: {} };
Object.defineProperty(document, 'visibilityState', {value: 'visible', writable: false});
Object.defineProperty(document, 'hidden', {value: false, writable: false});
document.dispatchEvent(new Event('visibilitychange'));
window.dispatchEvent(new Event('focus'));
} catch(e) {}
window.onerror = function(msg, url, line) {
try { RainBridge.onError("JS_ERR:" + msg + " at L" + line); } catch(e) {}
};
var rawPrompt = RAIN_PROMPT_TOKEN;
var seq = Date.now();
window.__rainSeq = seq;

// 2. 解析选项
var opts = {think: 0, search: 0};
var text = rawPrompt;
var m = rawPrompt.match(/^\sRAIN_OPTS\s+([^])]\s*/);
if (m) {
var kv = m[1].split(/\s+/);
for (var i = 0; i < kv.length; i++) {
var p = kv[i].split('=');
if (p.length === 2) {
if (p[0] === 'think') opts.think = (p[1] === '1' || p[1] === 'true') ? 1 : 0;
if (p[0] === 'search') opts.search = (p[1] === '1' || p[1] === 'true') ? 1 : 0;
}
}
text = rawPrompt.substring(m[0].length);
}

// 3. 工具函数
function visible(el) {
if (!el) return false;
try {
var r = el.getBoundingClientRect();
if (r.width <= 0 || r.height <= 0) return false;
var st = window.getComputedStyle(el);
return st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0';
} catch(e) { return false; }
}
function simulateClick(el) {
if (!el) return false;
try {
var r = el.getBoundingClientRect();
var o = {bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2};
el.dispatchEvent(new PointerEvent('pointerdown', o));
el.dispatchEvent(new MouseEvent('mousedown', o));
el.dispatchEvent(new PointerEvent('pointerup', o));
el.dispatchEvent(new MouseEvent('mouseup', o));
el.click();
return true;
} catch(e) { return false; }
}
function findInput() {
var candidates = document.querySelectorAll('textarea, [contenteditable="true"], div[role="textbox"]');
for (var i = 0; i < candidates.length; i++) {
if (visible(candidates[i])) return candidates[i];
}
return null;
}
var input = findInput();
if (!input) { RainBridge.onError("找不到输入框"); return; }

function findSendBtn() {
var btns = document.querySelectorAll('button, div[role="button"], div[class="send"], div[class="submit"]');
for (var i = 0; i < btns.length; i++) {
var b = btns[i];
if (b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
var label = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' + (b.className || '');
if (/send|发送/i.test(label) && !/stop|停止/i.test(label)) return b;
}
return null;
}

// 4. 注入文本并伪造 React/Vue 原生事件
input.focus();
try {
if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
var proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
if (setter) setter.call(input, text);
else input.value = text;
} else {
input.innerHTML = '';
document.execCommand('insertText', false, text);
}
input.dispatchEvent(new InputEvent('input', {bubbles: true, cancelable: true, inputType: 'insertText', data: text}));
input.dispatchEvent(new Event('change', {bubbles: true}));
} catch(e) { RainBridge.onError("输入注入失败: " + e); }

// 5. 发送并检测输入框是否清空
function inputEmptied() {
try {
if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT')
return (input.value || '').trim() === '';
return (input.textContent || '').trim() === '';
} catch(e) { return false; }
}

var attempts = [[false,false], [true,false], [false,true], [true,true]];
var ai = 0;
var isSent = false;

function pressEnter(shift, ctrl) {
input.focus();
var opts = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true, composed: true, shiftKey: !!shift, ctrlKey: !!ctrl};
try {
input.dispatchEvent(new KeyboardEvent('keydown', opts));
input.dispatchEvent(new KeyboardEvent('keypress', opts));
input.dispatchEvent(new KeyboardEvent('keyup', opts));
} catch(e) {
var ev = document.createEvent('KeyboardEvent');
ev.initKeyboardEvent('keydown', true, true, window, 'Enter', 13, '', !!ctrl, !!shift, '');
input.dispatchEvent(ev);
}
}

function forceClear() {
try {
if (input.tagName === 'TEXTAREA' || input.tagName === 'INPUT') {
var proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
if (setter) setter.call(input, '');
input.value = '';
} else {
input.innerHTML = '';
input.textContent = '';
}
input.dispatchEvent(new InputEvent('input', {bubbles: true, cancelable: true, inputType: 'deleteContentBackward', data: ''}));
input.dispatchEvent(new Event('change', {bubbles: true}));
} catch(e) {}
}

function tryNext() {
if (window.__rainSeq !== seq) return;
if (inputEmptied()) {
isSent = true;
return;
}
if (ai < attempts.length) {
var a = attempts[ai++];
pressEnter(a[0], a[1]);
setTimeout(tryNext, 100);
} else {
// 如果回车都不行，尝试点击发送按钮
var btn = findSendBtn();
if (btn) simulateClick(btn);
// 500ms 后如果还没清空，强行清空
setTimeout(function() {
if (!inputEmptied()) {
forceClear();
}
isSent = true;
}, 500);
}
}
setTimeout(tryNext, 200);

// 6. SSE Fetch 拦截器
var isIntercepting = true;
var fullText = "";
var t0 = Date.now();
var originalFetch = window.fetch;
window.fetch = function() {
var url = arguments[0] ? arguments[0].toString() : "";
// 匹配常见 AI 接口
var isChatApi = /completion|chat|generate|session|ask/i.test(url);
var p = originalFetch.apply(this, arguments);
if (!isChatApi) return p;
return p.then(function(response) {
if (!response.ok) return response;
var ct = response.headers.get('content-type') || '';
if (ct.indexOf('text/event-stream') === -1) return response;

var cloned = response.clone();
var reader = cloned.body.getReader();
var decoder = new TextDecoder("utf-8");

function readStream() {
reader.read().then(function(result) {
if (result.done) {
isIntercepting = false;
setTimeout(function() {
if (window.__rainSeq === seq) {
RainBridge.onResult(fullText || "DONE");
}
}, 500);
return;
}
var chunk = decoder.decode(result.value, {stream: true});
var lines = chunk.split('\n');
for(var i=0; i<lines.length; i++){
var line = lines[i].trim();
if(line.startsWith('data: ')) {
var jsonStr = line.substring(6);
if(jsonStr === '[DONE]') continue;
try {
var obj = JSON.parse(jsonStr);
var textChunk = "";
if (obj.choices && obj.choices[0] && obj.choices[0].delta && obj.choices[0].delta.content) {
textChunk = obj.choices[0].delta.content;
} else if (obj.text) {
textChunk = obj.text;
} else if (obj.v) {
textChunk = obj.v;
} else if (obj.content) {
textChunk = obj.content;
}
if (textChunk) {
fullText += textChunk;
try { RainBridge.onStream('content', textChunk); } catch(e) {}
}
} catch(e) {}
}
}
t0 = Date.now();
readStream();
}).catch(function(e) {
isIntercepting = false;
setTimeout(function() {
if (window.__rainSeq === seq) {
RainBridge.onResult(fullText || "ERROR: 流读取中断");
}
}, 500);
});
}
readStream();
return response;
});
};

// 7. 兜底超时检查
var timer = setInterval(function() {
if (window.__rainSeq !== seq) { clearInterval(timer); return; }
if (isIntercepting && Date.now() - t0 > 120000) {
clearInterval(timer);
isIntercepting = false;
RainBridge.onResult(fullText || "ERROR: 120秒未拦截到 SSE 数据流，请检查网页是否已登录");
}
}, 1000);
})();
