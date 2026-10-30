# Splits ember-client-26.2.html into script pieces in site/ so play.html works
# both on the website and when opened straight from this computer.
import json, os, glob
src = 'builds/ember-client-26.2.html' if os.path.exists('builds/ember-client-26.2.html') else 'ember-client-26.2.html'; out = 'site'
text = open(src, encoding='utf-8').read()
n = 20_000_000
parts = [text[i:i+n] for i in range(0, len(text), n)]
for f in glob.glob(os.path.join(out, 'game-part-*.js')): os.replace(f, f + '.old')
names = []
for i, p in enumerate(parts):
    name = 'game-part-%d.js' % i; names.append(name)
    with open(os.path.join(out, name), 'w', encoding='utf-8') as fh:
        fh.write('window.__emberParts[%d]=' % i + json.dumps(p, ensure_ascii=False) + ';\n')
open(os.path.join(out, 'game-parts.js'), 'w').write('window.EMBER_PARTS=' + json.dumps(names) + ';\n')
page = open(os.path.join(out, 'play.html'), encoding='utf-8').read()
start = page.index('<script>'); end = page.index('</script>', start) + len('</script>')
loader = '''<script>
// The game is stored in pieces next to this page (too big for one file).
// They load as plain scripts, which works on the website and from a file on your computer.
(function () {
  var PARTS = %s;
  var fill = document.getElementById("fill"), msg = document.getElementById("msg");
  window.__emberParts = [];
  var done = 0;
  function fail(name) {
    msg.className = "err";
    msg.textContent = "Couldn't load " + name + ". Make sure all the game-part files are in the same folder as this page.";
  }
  function next(i) {
    if (i === PARTS.length) {
      var html = window.__emberParts.join("");
      // Start all chunk builders at once instead of one, so turning fast doesn't stall.
      if (!/[?&](meshworkers|singlethread|nomesh)/.test(location.search)) {
        window.eaglercraftXOpts = window.eaglercraftXOpts || {};
        window.eaglercraftXOpts.meshWorkerCount = Math.max(1, Math.min(4, (navigator.hardwareConcurrency || 4) - 2));
      }
      window.__emberParts = null;
      document.open(); document.write(html); document.close();
      return;
    }
    var s = document.createElement("script");
    s.src = PARTS[i];
    s.onload = function () { fill.style.width = Math.round((i + 1) / PARTS.length * 100) + "%%"; next(i + 1); };
    s.onerror = function () { fail(PARTS[i]); };
    document.head.appendChild(s);
  }
  next(0);
})();
</script>''' % json.dumps(names)
page = page[:start] + loader + page[end:]
open(os.path.join(out, 'play.html'), 'w', encoding='utf-8').write(page)
print(len(parts), 'parts', [os.path.getsize(os.path.join(out, x)) for x in names])
