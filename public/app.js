/**
 * VoiceAgent Companion UI
 *
 * Lightweight dashboard that communicates with the native
 * VoiceCommandService via the WebView bridge interface.
 *
 * The REAL voice engine lives in MainActivity.java (Foreground Service).
 * This file is an optional UI companion — not the core engine.
 */

(function () {
  'use strict';

  const UI = {
    elements: {},

    init() {
      this.elements = {
        status: document.getElementById('va-status'),
        listening: document.getElementById('va-listening'),
        wakeWord: document.getElementById('va-wake'),
        lastCommand: document.getElementById('va-last'),
        response: document.getElementById('va-response'),
        toggleBtn: document.getElementById('va-toggle'),
      };
      this.bindEvents();
      this.pollStatus();
    },

    bindEvents() {
      if (this.elements.toggleBtn) {
        this.elements.toggleBtn.addEventListener('click', () => {
          this.callBridge('toggleListening', {});
        });
      }
    },

    pollStatus() {
      setInterval(() => {
        this.callBridge('getStatus', {}).then((r) => this.update(r)).catch(() => {});
      }, 500);
    },

    callBridge(method, params) {
      return new Promise((resolve, reject) => {
        if (window.VoiceAgentBridge) {
          try {
            const payload = JSON.stringify({ id: Date.now(), method, params });
            const result = window.VoiceAgentBridge.handleCommand(payload);
            resolve(result || {});
          } catch (e) {
            reject(e);
          }
        } else {
          // Demo mode — simulate data
          resolve(this.simulateStatus());
        }
      });
    },

    simulateStatus() {
      return {
        active: true,
        listening: true,
        wakeWordDetected: false,
        lastCommand: Math.random() > 0.7 ? 'washa taa' : null,
        lastAction: Math.random() > 0.8 ? 'Torch on' : null,
      };
    },

    update(data) {
      if (!data) return;
      const el = this.elements;
      if (el.status) el.status.textContent = data.active ? 'Active' : 'Standby';
      if (el.status) el.status.style.color = data.active ? '#4dc9f6' : '#667799';
      if (el.listening) {
        el.listening.textContent = data.listening ? 'Listening...' : 'Idle';
        el.listening.style.color = data.listening ? '#4dc9f6' : '#667799';
      }
      if (el.wakeWord) {
        el.wakeWord.textContent = data.wakeWordDetected ? 'Makoti active' : 'Say "hello makoti"';
        el.wakeWord.style.color = data.wakeWordDetected ? '#7c3aed' : '#556688';
      }
      if (el.lastCommand && data.lastCommand) {
        el.lastCommand.textContent = data.lastCommand;
        el.lastCommand.style.opacity = '1';
      }
      if (el.response && data.lastAction) {
        el.response.textContent = data.lastAction;
        el.response.style.opacity = '1';
      }
    },
  };

  // Auto-init when DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => UI.init());
  } else {
    UI.init();
  }

  // Expose for debugging
  window.VoiceAgentUI = UI;
})();
