// Node port of the Python mask rule -- CI cross-check
const fs = require("fs");
const path = "app/src/main/assets/mask_fixture.json";
const data = JSON.parse(fs.readFileSync(path, "utf8"));
const RX = new RegExp(data.pattern, "g");
function maskOne(s){ return s.length <= 8 ? s : s.slice(0,4) + "..." + s.slice(-4) + " [已脱敏]"; }
function scrub(text){
 if(!text) return text;
 return text.replace(RX, function(m){
 const args = Array.prototype.slice.call(arguments, 1, -2);
 for (let i = 0; i < args.length; i += 2) {
 if (args[i]) return args[i] + maskOne(args[i+1] || "");
 }
 return m;
 });
}
let failed = 0;
for (const c of data.cases) {
 const got = scrub(c.input);
 if (got !== c.expect) {
 console.log("FAIL " + c.name);
 console.log(" expect: " + JSON.stringify(c.expect));
 console.log(" got : " + JSON.stringify(got));
 failed++;
 } else {
 console.log("OK " + c.name);
 }
}
if (failed) process.exit(1);
console.log("js ALL PASS " + data.cases.length + " cases");
