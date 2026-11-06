/* Muntvinder Combi: bridge from the offline portfolio to the native scanner. */
(function () {
  "use strict";
  function openScanner() {
    if (window.NativeBridge && typeof window.NativeBridge.openScanner === "function") {
      window.NativeBridge.openScanner();
    }
  }
  const magnifier = '<svg viewBox="0 0 24 24" aria-hidden="true" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></svg>';

  const mobileNav = document.querySelector('.mobile-nav');
  if (mobileNav) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'mobile-nav-item';
    button.innerHTML = magnifier + '<span>Scanner</span>';
    button.addEventListener('click', openScanner);
    mobileNav.insertBefore(button, mobileNav.firstChild);
  }
  const desktopNav = document.querySelector('.side-links');
  if (desktopNav) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'nav-item';
    button.innerHTML = magnifier + 'Koopjescanner';
    button.addEventListener('click', openScanner);
    desktopNav.insertBefore(button, desktopNav.firstChild);
  }

  // Called only by the native scanner after the user chooses to register an offer.
  // This opens a draft form; it does NOT save a purchase or assume a bid was won.
  window.combiPrefill = function(raw) {
    let offer;
    try { offer = typeof raw === 'string' ? JSON.parse(raw) : raw; }
    catch (_) { return; }
    if (!offer || typeof offer !== 'object') return;
    const addButton = document.querySelector('.top-add');
    if (!addButton) return;
    addButton.click();
    const form = document.getElementById('record-form');
    if (!form) return;
    function setValue(selector, value) {
      const field = form.querySelector(selector);
      if (!field || value === undefined || value === null) return;
      field.value = String(value);
      field.dispatchEvent(new Event('input', {bubbles:true}));
      field.dispatchEvent(new Event('change', {bubbles:true}));
    }
    if (Number.isInteger(Number(offer.year)) && Number(offer.year)>=1900 && Number(offer.year)<=2100) {
      setValue('#form-year', offer.year);
    }
    if (typeof offer.type === 'string' && ['Proofset','Proofjaarset'].includes(offer.type)) {
      setValue('#form-type', offer.type);
    }
    if (typeof offer.title === 'string') setValue('#form-name', offer.title.slice(0,240));
    if (Number(offer.buy)>0 && Number.isFinite(Number(offer.buy))) {
      setValue('#fld-buy', String(offer.buy).replace('.',','));
    }
    if (typeof offer.note === 'string') setValue('#form-note', offer.note.slice(0,500));
    // Sale price deliberately remains blank; calculation is optional until sold.
  };
})();