// Fake mic for headless testing — يُحقن قبل تحميل الصفحة
(() => {
  const patch = () => {
    if (!navigator.mediaDevices) return false;
    const orig = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia = async (constraints) => {
      try {
        return await orig(constraints);
      } catch (e) {
        const AC = window.AudioContext || window.webkitAudioContext;
        const ctx = new AC();
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        const dst = ctx.createMediaStreamDestination();
        osc.frequency.value = 440 + Math.random() * 200;
        gain.gain.value = 0.05;
        osc.connect(gain);
        gain.connect(dst);
        osc.start();
        window.__fakeAudioCtx = ctx;
        return dst.stream;
      }
    };
    return true;
  };
  if (!patch()) {
    Object.defineProperty(navigator, 'mediaDevices', {
      get() {
        patch();
        return { getUserMedia: (c) => patch() && navigator.mediaDevices.getUserMedia(c) };
      },
      configurable: true,
    });
  }
})();
