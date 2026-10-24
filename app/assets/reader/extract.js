// The story's text, for the TV reader: where the page keeps most of its paragraphs. Returns JSON {title, site, image,
// blocks: [[tag, text]...]}, or "" when this doesn't look like an article (then the page shows as it is).
(function () {
  function meta(names) {
    for (var i = 0; i < names.length; i++) {
      var m = document.querySelector('meta[property="' + names[i] + '"], meta[name="' + names[i] + '"]');
      if (m && m.content) return m.content.trim();
    }
    return '';
  }
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function skipped(el) {
    for (var e = el; e && e !== document.body; e = e.parentElement) {
      var tag = e.tagName, id = (e.id + ' ' + (typeof e.className === 'string' ? e.className : '')).toLowerCase();
      if (tag === 'ASIDE' || tag === 'NAV' || tag === 'FOOTER' || tag === 'FIGCAPTION') return true;
      if (/(related|also-read|alsoread|recommend|newsletter|comment|share|social|promo|advert|subscribe|trending|sidebar|cookie|consent)/.test(id)) return true;
    }
    return false;
  }

  // Score each paragraph's parent (and, half, its grandparent) by the text it holds.
  var ps = document.querySelectorAll('p'), scores = [], nodes = [];
  function add(node, n) {
    var i = nodes.indexOf(node);
    if (i < 0) { nodes.push(node); scores.push(n); } else scores[i] += n;
  }
  for (var i = 0; i < ps.length; i++) {
    var t = clean(ps[i].innerText);
    if (t.length < 40 || skipped(ps[i])) continue;
    if (ps[i].parentElement) add(ps[i].parentElement, t.length);
    if (ps[i].parentElement && ps[i].parentElement.parentElement) add(ps[i].parentElement.parentElement, t.length / 2);
  }
  var best = null, top = 0;
  for (var j = 0; j < nodes.length; j++) if (scores[j] > top) { top = scores[j]; best = nodes[j]; }
  if (!best) return '';

  var blocks = [], total = 0, seen = {};
  var parts = best.querySelectorAll('p, h2, h3, blockquote, li');
  for (var k = 0; k < parts.length; k++) {
    var el = parts[k];
    if (skipped(el)) continue;
    var text = clean(el.innerText), tag = el.tagName.toLowerCase();
    if (tag === 'li' && el.closest('p')) continue;
    if (!text || seen[text]) continue;
    if (tag === 'p' && text.length < 25) continue;
    if (tag === 'li' && text.length < 40) continue;
    if (/^(also read|read more|advertisement|click here|follow us|subscribe)/i.test(text)) continue;
    seen[text] = true;
    blocks.push([tag === 'li' ? 'p' : tag, text]);
    if (tag === 'p') total += text.length;
  }
  if (blocks.length < 3 || total < 600) {
    // No paragraphs (some sites write their stories as text and <br>s in a <div>): the element holding the
    // most loose text, split at its line breaks.
    var inline = /^(A|B|I|EM|STRONG|SPAN|U|SMALL|SUP|SUB|BR|MARK|Q|ABBR|TIME)$/;
    var all = document.querySelectorAll('div, section, article, main, td'), bestText = null, most = 0;
    for (var a = 0; a < all.length; a++) {
      var el = all[a], n = 0;
      for (var c = el.firstChild; c; c = c.nextSibling) {
        if (c.nodeType === 3) n += clean(c.nodeValue).length;
        else if (c.nodeType === 1 && inline.test(c.tagName)) n += clean(c.innerText).length;
      }
      if (n > most && !skipped(el)) { most = n; bestText = el; }
    }
    if (!bestText || most < 600) return '';
    blocks = []; total = 0; seen = {};
    var lines = (bestText.innerText || '').split(/\n+/);
    for (var l = 0; l < lines.length; l++) {
      var line = clean(lines[l]);
      if (line.length < 25 || seen[line]) continue;
      if (/^(also read|read more|advertisement|click here|follow us|subscribe)/i.test(line)) continue;
      seen[line] = true;
      blocks.push(['p', line]);
      total += line.length;
    }
    if (blocks.length < 2 || total < 600) return '';
  }
  var h1 = document.querySelector('h1');
  return JSON.stringify({
    title: clean(h1 && h1.innerText) || meta(['og:title', 'twitter:title']) || document.title,
    site: meta(['og:site_name', 'application-name']) || location.hostname.replace(/^www\./, ''),
    image: meta(['og:image', 'twitter:image']),
    blocks: blocks
  });
})();
