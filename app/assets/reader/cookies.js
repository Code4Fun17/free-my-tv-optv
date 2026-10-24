// Cookie banners: chooses "Reject all" / "Only necessary" where the site offers it, never "Accept all", then hides
// what is left of the banner. Returns how many buttons it pressed. Run by NewsActivity on every story page.
(function () {
  var pressed = 0;
  // The big consent platforms, through their own "reject everything" calls.
  try { if (window.OneTrust && OneTrust.RejectAll) { OneTrust.RejectAll(); pressed++; } } catch (e) {}
  try { if (window.Didomi && Didomi.setUserDisagreeToAll) { Didomi.setUserDisagreeToAll(); pressed++; } } catch (e) {}
  try { if (window.Cookiebot && Cookiebot.submitCustomConsent) { Cookiebot.submitCustomConsent(false, false, false); pressed++; } } catch (e) {}
  try { if (window.__tcfapi && window.__cmp && __cmp.rejectAll) { __cmp.rejectAll(); pressed++; } } catch (e) {}

  var reject = [
    /^reject( all)?( cookies)?$/i, /^decline( all)?( cookies)?$/i, /^deny( all)?$/i, /^refuse( all)?$/i,
    /^disagree( (to|with) all)?$/i, /^(i )?do not (accept|agree)$/i, /^no,? thanks$/i,
    /^continue without (accepting|agreeing)$/i,
    /^(accept |allow |use |save )?(only )?(strictly )?(necessary|essential|required)( cookies)?( only)?$/i,
    /^(necessary|essential) cookies only$/i, /^reject (non-essential|optional|additional)( cookies)?$/i
  ];
  var buttons = document.querySelectorAll('button, a, [role=button], input[type=button], input[type=submit]');
  for (var i = 0; i < buttons.length; i++) {
    var b = buttons[i];
    var text = ((b.innerText || b.value || b.getAttribute('aria-label') || '') + '').replace(/\s+/g, ' ').trim();
    if (!text || text.length > 45) continue;
    for (var j = 0; j < reject.length; j++) {
      if (reject[j].test(text)) {
        try { b.click(); pressed++; } catch (e) {}
        break;
      }
    }
  }

  // Whatever banner is still up (many only offer "Accept"): hidden, without answering it.
  var known = '#onetrust-consent-sdk, #onetrust-banner-sdk, .fc-consent-root, #didomi-host, .qc-cmp2-container,'
    + ' #CybotCookiebotDialog, #cmpbox, #cmpbox2, [id^=sp_message_container], .cc-window, #cookie-law-info-bar,'
    + ' #cookie-banner, .cookie-banner, #cookieConsent, .cookie-consent, #gdpr-banner, .gdpr-banner, #usercentrics-root';
  var found = document.querySelectorAll(known);
  for (var k = 0; k < found.length; k++) found[k].style.setProperty('display', 'none', 'important');
  var maybe = document.querySelectorAll('[id*=cookie i], [class*=cookie i], [id*=consent i], [class*=consent i], [id*=gdpr i], [class*=gdpr i]');
  for (var m = 0; m < maybe.length; m++) {
    var el = maybe[m], st = getComputedStyle(el);
    if ((st.position === 'fixed' || st.position === 'sticky') && (el.innerText || '').length < 1500) {
      el.style.setProperty('display', 'none', 'important');
    }
  }
  document.documentElement.style.overflow = '';
  if (document.body) document.body.style.overflow = '';
  return pressed;
})();
