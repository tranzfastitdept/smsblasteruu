/**
 * BULK SMS CAMPAIGN MANAGER - JAVASCRIPT CONTROLLER
 * Connects modern responsive sidebar UI with Android Kotlin bridge
 */

(function () {
  'use strict';

  // State Management
  const state = {
    currentScreen: 'screen-dashboard',
    simCards: [],
    recipientLists: [],
    templates: [],
    activeCampaign: null,
    generatedNumbers: [],
    optOutContacts: [],
    audioEnabled: true,
    importData: {
      headers: [],
      rows: [],
      validRows: [],
      duplicates: 0,
      invalid: 0
    },
    preferences: {
      sending_interval_ms: 5000,
      selected_sim_sub_id: -1,
      default_template_id: 1,
      max_campaign_size: 500,
      show_countdown: true,
      notifications_enabled: true,
      theme_preference: 'system',
      default_prefix: '0992438',
      require_confirmation: true,
      mask_numbers_by_default: true
    },
    maskNumbers: true,
    activePollInterval: null,
    lastCountdownTotal: 5
  };

  // Web Audio Synthesizer for cool tactile micro-interactions
  const AudioEffects = {
    ctx: null,
    init: function () {
      if (!this.ctx && (window.AudioContext || window.webkitAudioContext)) {
        try {
          const AudioCtx = window.AudioContext || window.webkitAudioContext;
          this.ctx = new AudioCtx();
        } catch (e) {}
      }
    },
    playClick: function () {
      if (!state.audioEnabled) return;
      this.init();
      if (!this.ctx) return;
      try {
        if (this.ctx.state === 'suspended') this.ctx.resume();
        const osc = this.ctx.createOscillator();
        const gain = this.ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(800, this.ctx.currentTime);
        osc.frequency.exponentialRampToValueAtTime(400, this.ctx.currentTime + 0.04);
        gain.gain.setValueAtTime(0.08, this.ctx.currentTime);
        gain.gain.linearRampToValueAtTime(0.001, this.ctx.currentTime + 0.04);
        osc.connect(gain);
        gain.connect(this.ctx.destination);
        osc.start();
        osc.stop(this.ctx.currentTime + 0.04);
      } catch (e) {}
    },
    playSuccess: function () {
      if (!state.audioEnabled) return;
      this.init();
      if (!this.ctx) return;
      try {
        if (this.ctx.state === 'suspended') this.ctx.resume();
        const now = this.ctx.currentTime;
        const osc = this.ctx.createOscillator();
        const gain = this.ctx.createGain();
        osc.type = 'triangle';
        osc.frequency.setValueAtTime(523.25, now); // C5
        osc.frequency.setValueAtTime(659.25, now + 0.08); // E5
        gain.gain.setValueAtTime(0.12, now);
        gain.gain.linearRampToValueAtTime(0.001, now + 0.22);
        osc.connect(gain);
        gain.connect(this.ctx.destination);
        osc.start(now);
        osc.stop(now + 0.22);
      } catch (e) {}
    },
    playAlert: function () {
      if (!state.audioEnabled) return;
      this.init();
      if (!this.ctx) return;
      try {
        if (this.ctx.state === 'suspended') this.ctx.resume();
        const now = this.ctx.currentTime;
        const osc = this.ctx.createOscillator();
        const gain = this.ctx.createGain();
        osc.type = 'sawtooth';
        osc.frequency.setValueAtTime(280, now);
        osc.frequency.linearRampToValueAtTime(200, now + 0.15);
        gain.gain.setValueAtTime(0.12, now);
        gain.gain.linearRampToValueAtTime(0.001, now + 0.15);
        osc.connect(gain);
        gain.connect(this.ctx.destination);
        osc.start(now);
        osc.stop(now + 0.15);
      } catch (e) {}
    }
  };

  // ==============================================================
  // NATIVE BRIDGE ABSTRACTION & SAFE FALLBACKS
  // ==============================================================
  const Native = {
    isAvailable: function () {
      return typeof window.AndroidBridge !== 'undefined' || typeof window.BulkSmsBridge !== 'undefined';
    },

    getBridge: function () {
      return window.AndroidBridge || window.BulkSmsBridge || null;
    },

    getDashboardStats: function () {
      const b = this.getBridge();
      if (b && b.getDashboardStats) {
        try { return JSON.parse(b.getDashboardStats()); } catch (e) { console.error(e); }
      }
      return { totalCampaigns: 0, totalRecipients: 0, queued: 0, submitted: 0, delivered: 0, failed: 0, awaiting: 0, sentToday: 0, mostRecentCampaign: 'None', lastActivityTimestamp: 0 };
    },

    getSimCards: function () {
      const b = this.getBridge();
      if (b && b.getSimCards) {
        try { return JSON.parse(b.getSimCards()); } catch (e) { console.error(e); }
      }
      return [{ subscriptionId: -1, simSlotIndex: 0, displayName: 'Default Device Carrier SIM', carrierName: 'Active SIM', countryIso: '', number: '' }];
    },

    checkPermissions: function () {
      const b = this.getBridge();
      if (b && b.checkPermissions) {
        try { return JSON.parse(b.checkPermissions()); } catch (e) { console.error(e); }
      }
      return { smsHardware: true, smsPermissions: true, notificationPermission: true, cellularAvailable: true };
    },

    requestPermissions: function () {
      const b = this.getBridge();
      if (b && b.requestPermissions) b.requestPermissions();
    },

    generateNumbers: function (prefix, startSuffix, endSuffix, maxLimit) {
      const b = this.getBridge();
      if (b && b.generateNumbers) {
        try { return JSON.parse(b.generateNumbers(prefix, startSuffix, endSuffix, maxLimit)); } catch (e) { console.error(e); }
      }
      return fallbackGenerateNumbers(prefix, startSuffix, endSuffix, maxLimit);
    },

    saveRecipientList: function (name, sourceType, contacts) {
      const b = this.getBridge();
      if (b && b.saveRecipientList) {
        try { return JSON.parse(b.saveRecipientList(name, sourceType, JSON.stringify(contacts))); } catch (e) { console.error(e); }
      }
      return { success: true, recipientListId: Date.now(), savedCount: contacts.length };
    },

    getRecipientLists: function () {
      const b = this.getBridge();
      if (b && b.getRecipientLists) {
        try { return JSON.parse(b.getRecipientLists()); } catch (e) { console.error(e); }
      }
      return [];
    },

    getTemplates: function () {
      const b = this.getBridge();
      if (b && b.getTemplates) {
        try { return JSON.parse(b.getTemplates()); } catch (e) { console.error(e); }
      }
      return [
        { id: 1, name: 'Account Alert', content: 'Hello {name}, this is an urgent notification regarding your account.' },
        { id: 2, name: 'Appointment Reminder', content: 'Dear {name}, this is a reminder for your upcoming appointment. Please reply to confirm.' },
        { id: 3, name: 'Service Notice', content: 'Notice for {name}: Scheduled maintenance will occur tonight. Service will resume shortly.' }
      ];
    },

    saveTemplate: function (id, name, content) {
      const b = this.getBridge();
      if (b && b.saveTemplate) {
        try { return JSON.parse(b.saveTemplate(id, name, content)); } catch (e) { console.error(e); }
      }
      return { success: true, id: Date.now() };
    },

    createCampaign: function (name, template, listId, intervalMs, subId, allowUnverified) {
      const b = this.getBridge();
      if (b && b.createCampaign) {
        try { return JSON.parse(b.createCampaign(name, template, listId, intervalMs, subId, allowUnverified)); } catch (e) { console.error(e); }
      }
      return { success: true, campaignId: Date.now() };
    },

    startCampaign: function (campaignId) {
      const b = this.getBridge();
      if (b && b.startCampaign) {
        try { return JSON.parse(b.startCampaign(campaignId)); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    pauseCampaign: function () {
      const b = this.getBridge();
      if (b && b.pauseCampaign) {
        try { return JSON.parse(b.pauseCampaign()); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    resumeCampaign: function () {
      const b = this.getBridge();
      if (b && b.resumeCampaign) {
        try { return JSON.parse(b.resumeCampaign()); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    stopCampaign: function () {
      const b = this.getBridge();
      if (b && b.stopCampaign) {
        try { return JSON.parse(b.stopCampaign()); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    sendTestSms: function (phone, message, subId) {
      const b = this.getBridge();
      if (b && b.sendTestSms) {
        try { return JSON.parse(b.sendTestSms(phone, message, subId)); } catch (e) { console.error(e); }
      }
      return { success: true, correlationId: 'mock-test-' + Date.now() };
    },

    getActiveCampaignState: function () {
      const b = this.getBridge();
      if (b && b.getActiveCampaignState) {
        try { return JSON.parse(b.getActiveCampaignState()); } catch (e) { console.error(e); }
      }
      return null;
    },

    getLiveLogs: function () {
      const b = this.getBridge();
      if (b && b.getLiveLogs) {
        try { return JSON.parse(b.getLiveLogs()); } catch (e) { console.error(e); }
      }
      return [];
    },

    getCampaigns: function (limit, offset) {
      const b = this.getBridge();
      if (b && b.getCampaigns) {
        try { return JSON.parse(b.getCampaigns(limit || 50, offset || 0)); } catch (e) { console.error(e); }
      }
      return [];
    },

    getMessageJobs: function (campaignId, statusFilter, limit, offset) {
      const b = this.getBridge();
      if (b && b.getMessageJobs) {
        try { return JSON.parse(b.getMessageJobs(campaignId || 0, statusFilter || 'ALL', limit || 100, offset || 0)); } catch (e) { console.error(e); }
      }
      return [];
    },

    getReportsData: function (startDate, endDate, campaignId) {
      const b = this.getBridge();
      if (b && b.getReportsData) {
        try { return JSON.parse(b.getReportsData(startDate || 0, endDate || 0, campaignId || 0)); } catch (e) { console.error(e); }
      }
      return { totalRecipients: 0, submitted: 0, delivered: 0, failed: 0, queued: 0, cancelled: 0, unknown: 0, successRate: '0.0', failureRate: '0.0' };
    },

    getOptOutContacts: function () {
      const b = this.getBridge();
      if (b && b.getOptOutContacts) {
        try { return JSON.parse(b.getOptOutContacts()); } catch (e) { console.error(e); }
      }
      return [];
    },

    addOptOutContact: function (phone, reason) {
      const b = this.getBridge();
      if (b && b.addOptOutContact) {
        try { return JSON.parse(b.addOptOutContact(phone, reason)); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    removeOptOutContact: function (id) {
      const b = this.getBridge();
      if (b && b.removeOptOutContact) {
        try { return JSON.parse(b.removeOptOutContact(id)); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    getPreferences: function () {
      const b = this.getBridge();
      if (b && b.getPreferences) {
        try { return JSON.parse(b.getPreferences()); } catch (e) { console.error(e); }
      }
      return state.preferences;
    },

    savePreferences: function (prefs) {
      const b = this.getBridge();
      if (b && b.savePreferences) {
        try { return JSON.parse(b.savePreferences(JSON.stringify(prefs))); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    resetPreferences: function () {
      const b = this.getBridge();
      if (b && b.resetPreferences) {
        try { return JSON.parse(b.resetPreferences()); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    clearLocalData: function (clearType) {
      const b = this.getBridge();
      if (b && b.clearLocalData) {
        try { return JSON.parse(b.clearLocalData(clearType)); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    getFormattedCampaignReport: function (campaignId) {
      const b = this.getBridge();
      if (b && b.getFormattedCampaignReport) {
        return b.getFormattedCampaignReport(campaignId);
      }
      const now = new Date().toISOString().replace('T', ' ').substring(0, 19);
      return `SMS BLAST REPORT ${now}\nACR SERVICES\nSUCCESSFUL: 0\nUNSUCCESSFUL: 0\nTOTAL OVERALL: 0\n\nINDIVIDUAL NUMBER REPORTS:\n\n`;
    },

    sendCampaignEmailReport: function (campaignId, customEmail) {
      const b = this.getBridge();
      if (b && b.sendCampaignEmailReport) {
        try { return JSON.parse(b.sendCampaignEmailReport(campaignId, customEmail || '')); } catch (e) { console.error(e); }
      }
      return { success: false, errorMessage: 'Native bridge unavailable' };
    },

    sendEmailReport: function (email, subject, body) {
      const b = this.getBridge();
      if (b && b.sendEmailReport) {
        try { return JSON.parse(b.sendEmailReport(email, subject, body)); } catch (e) { console.error(e); }
      }
      return { success: true };
    },

    testSmtpConnection: function (targetEmail) {
      const b = this.getBridge();
      if (b && b.testSmtpConnection) {
        try { return JSON.parse(b.testSmtpConnection(targetEmail || '')); } catch (e) { console.error(e); }
      }
      return { success: false, errorMessage: 'Native bridge unavailable' };
    }
  };

  function fallbackGenerateNumbers(prefix, startSuffix, endSuffix, maxLimit) {
    const cleanPrefix = (prefix || '').trim();
    const startVal = parseInt(startSuffix, 10);
    const endVal = parseInt(endSuffix, 10);
    if (isNaN(startVal) || isNaN(endVal) || startVal >= endVal) {
      return { success: false, errorMessage: 'Invalid start/end suffixes' };
    }
    const numbers = [];
    for (let i = startVal; i < endVal; i++) {
      const pad = String(i).padStart(4, '0');
      numbers.push(cleanPrefix + pad);
    }
    return { success: true, numbers: numbers, totalCount: numbers.length, duplicateCount: 0 };
  }

  // ==============================================================
  // SIDEBAR NAVIGATION & VIEW ROUTER
  // ==============================================================
  const SCREEN_TITLES = {
    'screen-dashboard': { title: 'Dashboard', sub: 'Carrier SIM Messaging' },
    'screen-test': { title: 'Quick SMS Test', sub: 'Handset Delivery Diagnostic' },
    'screen-generator': { title: 'Number Generator', sub: 'Zero-Padded Series Creator' },
    'screen-import': { title: 'Contact Import', sub: 'CSV, TXT, Excel Spreadsheets' },
    'screen-optout': { title: 'Suppression List', sub: 'Opt-Out & Blacklist Management' },
    'screen-composer': { title: 'Campaign Composer', sub: 'Message Authoring & Templates' },
    'screen-active': { title: 'Active Monitor', sub: 'Live Telephony & Queue Status' },
    'screen-history': { title: 'Campaign History', sub: 'Past Broadcast Logs' },
    'screen-reports': { title: 'Reports & Audit', sub: 'Correlation Telemetry & Export' },
    'screen-settings': { title: 'Preferences', sub: 'System Configuration & Safety' }
  };

  window.navigateTo = function (screenId) {
    AudioEffects.playClick();
    const screens = document.querySelectorAll('.screen-view');
    screens.forEach(s => s.classList.remove('active'));

    const target = document.getElementById(screenId);
    if (target) {
      target.classList.add('active');
      state.currentScreen = screenId;
    }

    // Update sidebar nav active links
    const navLinks = document.querySelectorAll('.nav-link');
    navLinks.forEach(link => {
      if (link.getAttribute('data-target') === screenId) {
        link.classList.add('active');
      } else {
        link.classList.remove('active');
      }
    });

    // Update topbar title & subtitle
    const info = SCREEN_TITLES[screenId] || { title: 'Bulk SMS', sub: 'Campaign Manager' };
    document.getElementById('topbar-page-title').textContent = info.title;
    document.getElementById('topbar-page-sub').textContent = info.sub;

    // Close mobile sidebar if open
    window.toggleSidebar(false);

    // Scroll main viewport to top smoothly
    document.getElementById('main-viewport').scrollTop = 0;

    // Trigger screen-specific data loaders
    if (screenId === 'screen-dashboard') loadDashboard();
    if (screenId === 'screen-test') loadTestScreen();
    if (screenId === 'screen-import') loadRecipientLists();
    if (screenId === 'screen-optout') loadOptOutList();
    if (screenId === 'screen-composer') loadComposer();
    if (screenId === 'screen-history') loadCampaignHistory();
    if (screenId === 'screen-reports') loadReports();
    if (screenId === 'screen-settings') loadSettings();
  };

  window.toggleSidebar = function (open) {
    const sidebar = document.getElementById('app-sidebar');
    const backdrop = document.getElementById('sidebar-backdrop');
    if (open) {
      sidebar.classList.add('open');
      backdrop.classList.add('open');
    } else {
      sidebar.classList.remove('open');
      backdrop.classList.remove('open');
    }
  };

  window.toggleAudio = function () {
    state.audioEnabled = !state.audioEnabled;
    document.getElementById('audio-icon-on').classList.toggle('hidden', !state.audioEnabled);
    document.getElementById('audio-icon-off').classList.toggle('hidden', state.audioEnabled);
    if (state.audioEnabled) AudioEffects.playSuccess();
    showToast(state.audioEnabled ? 'Audio feedback enabled' : 'Audio feedback muted');
  };

  window.handleAppBack = function () {
    const openModal = document.querySelector('.modal-overlay:not(.hidden)');
    if (openModal) {
      openModal.classList.add('hidden');
      return 'HANDLED';
    }
    const sidebar = document.getElementById('app-sidebar');
    if (sidebar.classList.contains('open')) {
      toggleSidebar(false);
      return 'HANDLED';
    }
    if (state.currentScreen !== 'screen-dashboard') {
      window.navigateTo('screen-dashboard');
      return 'HANDLED';
    }
    return 'DEFAULT';
  };

  // ==============================================================
  // INITIALIZATION
  // ==============================================================
  window.addEventListener('DOMContentLoaded', () => {
    initApp();
  });

  function initApp() {
    loadSettings();
    loadDashboard();
    checkPermissions();
    startActiveMonitorPoll();
  }

  // ==============================================================
  // SCREEN 1: DASHBOARD
  // ==============================================================
  window.loadDashboard = function () {
    const stats = Native.getDashboardStats();
    
    // Animate numbers
    animateNumber('stat-total-campaigns', stats.totalCampaigns || 0);
    animateNumber('stat-total-recipients', stats.totalRecipients || 0);
    animateNumber('stat-sent-today', stats.sentToday || 0);
    animateNumber('stat-confirmed-delivered', stats.delivered || 0);
    animateNumber('stat-messages-queued', stats.queued || 0);
    animateNumber('stat-failed-messages', stats.failed || 0);

    document.getElementById('stat-recent-campaign').textContent = stats.mostRecentCampaign || 'None';

    if (stats.lastActivityTimestamp && stats.lastActivityTimestamp > 0) {
      document.getElementById('stat-last-activity').textContent = formatTimestamp(stats.lastActivityTimestamp);
    } else {
      document.getElementById('stat-last-activity').textContent = 'No activity recorded';
    }

    // SIM subscriptions
    state.simCards = Native.getSimCards();
    renderSimList(state.simCards);
  };

  function renderSimList(sims) {
    const container = document.getElementById('sim-cards-list');
    if (!sims || sims.length === 0) {
      container.innerHTML = '<div class="empty-state">No SIM card detected in device.</div>';
      return;
    }

    container.innerHTML = sims.map(sim => `
      <div class="sim-subscription-row" style="display:flex; justify-content:space-between; align-items:center; padding:10px 0; border-bottom:1px solid var(--border); font-size:0.86rem;">
        <div style="display:flex; align-items:center; gap:10px;">
          <span class="badge badge-success">SIM</span>
          <div>
            <strong>${escapeHtml(sim.displayName || 'SIM ' + (sim.simSlotIndex + 1))}</strong>
            <span style="color:var(--text-muted); margin-left:6px; font-size:0.75rem;">${escapeHtml(sim.carrierName || 'Carrier')}</span>
          </div>
        </div>
        <div style="font-family:var(--font-mono); color:var(--cyan); font-weight:700;">
          ${sim.number ? escapeHtml(maskNumber(sim.number)) : 'Slot ' + (sim.simSlotIndex + 1)}
        </div>
      </div>
    `).join('');

    // Update sidebar footer
    if (sims[0]) {
      document.getElementById('sidebar-sim-name').textContent = sims[0].displayName || 'SIM Ready';
      document.getElementById('sidebar-sim-carrier').textContent = sims[0].carrierName || 'Direct Carrier';
    }
  }

  function checkPermissions() {
    const perm = Native.checkPermissions();
    const banner = document.getElementById('permission-alert');
    if (!perm.smsPermissions) {
      banner.classList.remove('hidden');
    } else {
      banner.classList.add('hidden');
    }
  }

  window.requestNativePermissions = function () {
    AudioEffects.playClick();
    Native.requestPermissions();
  };

  window.onPermissionsResult = function (allGranted) {
    checkPermissions();
    loadDashboard();
    if (allGranted) {
      AudioEffects.playSuccess();
      showToast('SMS permissions granted!');
    } else {
      showToast('SMS permissions are required to broadcast.');
    }
  };

  // ==============================================================
  // SCREEN 2: QUICK TEST SMS (Notable Function!)
  // ==============================================================
  function loadTestScreen() {
    state.simCards = Native.getSimCards();
    const simSelect = document.getElementById('test-sim-select');
    simSelect.innerHTML = state.simCards.map(s => `
      <option value="${s.subscriptionId}">${escapeHtml(s.displayName)} (${escapeHtml(s.carrierName)})</option>
    `).join('');
  }

  window.fillSelfTestNumber = function () {
    AudioEffects.playClick();
    document.getElementById('test-phone-input').value = state.preferences.default_prefix + '0001';
  };

  window.clearTestForm = function () {
    AudioEffects.playClick();
    document.getElementById('test-phone-input').value = '';
    document.getElementById('test-result-box').classList.add('hidden');
  };

  window.executeQuickTestSms = function () {
    AudioEffects.playClick();
    const phone = document.getElementById('test-phone-input').value.trim();
    const message = document.getElementById('test-message-input').value.trim();
    const simSubId = parseInt(document.getElementById('test-sim-select').value, 10);

    if (!phone) {
      AudioEffects.playAlert();
      showToast('Please enter a destination phone number.');
      return;
    }
    if (!message) {
      AudioEffects.playAlert();
      showToast('Please enter test message content.');
      return;
    }

    const resultBox = document.getElementById('test-result-box');
    const badge = document.getElementById('test-result-status-badge');
    const timeLabel = document.getElementById('test-result-time');
    const details = document.getElementById('test-result-details');

    resultBox.classList.remove('hidden');
    badge.className = 'badge badge-cyan';
    badge.textContent = 'TRANSMITTING';
    timeLabel.textContent = new Date().toLocaleTimeString();
    details.textContent = `Attempting single SMS send to ${phone} via selected SIM...`;

    const res = Native.sendTestSms(phone, message.replace('{time}', new Date().toLocaleTimeString()), simSubId);
    if (res.success) {
      AudioEffects.playSuccess();
      badge.className = 'badge badge-success';
      badge.textContent = 'SUBMITTED TO CARRIER';
      details.innerHTML = `<strong>Success:</strong> Message accepted by Android telephony system.<br><small style="opacity:0.7;">Correlation ID: ${res.correlationId || 'N/A'}</small>`;
      showToast('Test SMS submitted to mobile carrier!');
    } else {
      AudioEffects.playAlert();
      badge.className = 'badge badge-red';
      badge.textContent = 'FAILED';
      details.innerHTML = `<strong>Error:</strong> ${escapeHtml(res.errorMessage || 'SMS transmission failed.')}`;
      showToast('Test SMS failed: ' + (res.errorMessage || 'Unknown error'));
    }
  };

  // ==============================================================
  // SCREEN 3: NUMBER GENERATOR
  // ==============================================================
  window.generateNumberSeries = function () {
    AudioEffects.playClick();
    const prefix = document.getElementById('gen-prefix').value.trim();
    const start = document.getElementById('gen-start').value.trim();
    const end = document.getElementById('gen-end').value.trim();
    const maxLimit = state.preferences.max_campaign_size || 1000;

    if (!prefix) {
      AudioEffects.playAlert();
      showToast('Please enter a phone number prefix series.');
      return;
    }
    if (start.length !== 4 || !/^\d{4}$/.test(start)) {
      AudioEffects.playAlert();
      showToast('Start suffix must be exactly 4 digits (e.g. 0000).');
      return;
    }
    if (end.length !== 4 || !/^\d{4}$/.test(end)) {
      AudioEffects.playAlert();
      showToast('End suffix must be exactly 4 digits (e.g. 0100).');
      return;
    }

    const result = Native.generateNumbers(prefix, start, end, maxLimit);
    if (!result.success) {
      AudioEffects.playAlert();
      showToast(result.errorMessage || 'Generation failed');
      return;
    }

    AudioEffects.playSuccess();
    state.generatedNumbers = result.numbers || [];

    document.getElementById('gen-count-badge').textContent = state.generatedNumbers.length;
    document.getElementById('gen-range-label').textContent = `${start} - ${String(parseInt(end, 10) - 1).padStart(4, '0')}`;

    const container = document.getElementById('gen-preview-container');
    const previewItems = state.generatedNumbers.slice(0, 50).map((num, i) => `
      <div style="display:flex; justify-content:space-between; padding:5px 8px; border-bottom:1px solid var(--border); font-family:var(--font-mono); font-size:0.84rem;">
        <span style="opacity:0.6;">#${i + 1}</span>
        <strong>${escapeHtml(num)}</strong>
        <span class="status-tag Queued">Unverified</span>
      </div>
    `).join('');

    const moreText = state.generatedNumbers.length > 50
      ? `<div style="padding:10px; text-align:center; color:var(--text-muted); font-size:0.8rem;">... and ${state.generatedNumbers.length - 50} more numbers</div>`
      : '';

    container.innerHTML = previewItems + moreText;

    document.getElementById('btn-save-generated').disabled = false;
    document.getElementById('btn-export-gen-csv').disabled = false;
    document.getElementById('btn-export-gen-txt').disabled = false;

    showToast(`Generated ${state.generatedNumbers.length} numbers with zero-padding.`);
  };

  window.saveGeneratedNumbers = function () {
    AudioEffects.playClick();
    if (!state.generatedNumbers || state.generatedNumbers.length === 0) return;

    const ack = document.getElementById('gen-consent-ack').checked;
    if (!ack) {
      AudioEffects.playAlert();
      showToast('Please acknowledge the consent requirement checkbox.');
      return;
    }

    const listName = document.getElementById('gen-list-name').value.trim() || `Generated List (${state.generatedNumbers.length})`;

    const contacts = state.generatedNumbers.map(num => ({
      phoneNumber: num,
      originalPhoneNumber: num,
      displayName: '',
      groupName: 'Generated',
      consentStatus: 0,
      consentSource: 'Generator'
    }));

    const res = Native.saveRecipientList(listName, 'generator', contacts);
    if (res.success) {
      AudioEffects.playSuccess();
      showToast(`Saved ${res.savedCount} contacts to list '${listName}'!`);
      loadRecipientLists();
      loadDashboard();
    } else {
      AudioEffects.playAlert();
      showToast(res.errorMessage || 'Failed to save list');
    }
  };

  window.exportGenerated = function (type) {
    AudioEffects.playClick();
    if (!state.generatedNumbers || state.generatedNumbers.length === 0) return;
    let content = '';
    let filename = '';
    let mime = '';

    if (type === 'csv') {
      content = 'phone_number,consent_status,source\n' + state.generatedNumbers.map(n => `"${n}",0,"generator"`).join('\n');
      filename = `generated_series_${Date.now()}.csv`;
      mime = 'text/csv';
    } else {
      content = state.generatedNumbers.join('\n');
      filename = `generated_series_${Date.now()}.txt`;
      mime = 'text/plain';
    }

    downloadBlob(content, filename, mime);
    showToast(`Exported ${type.toUpperCase()}`);
  };

  // ==============================================================
  // SCREEN 4: CONTACT IMPORT
  // ==============================================================
  window.handleFileSelected = function (e) {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    processImportFile(file);
  };

  const dropzone = document.getElementById('file-dropzone');
  if (dropzone) {
    dropzone.addEventListener('dragover', (e) => {
      e.preventDefault();
      dropzone.classList.add('dragover');
    });
    dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
    dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragover');
      if (e.dataTransfer.files && e.dataTransfer.files[0]) {
        processImportFile(e.dataTransfer.files[0]);
      }
    });
  }

  function processImportFile(file) {
    AudioEffects.playClick();
    const fileName = file.name;
    const ext = fileName.split('.').pop().toLowerCase();
    document.getElementById('import-filename-badge').textContent = fileName;
    document.getElementById('import-list-name').value = fileName.replace(/\.[^/.]+$/, '');

    if (ext === 'txt') {
      const reader = new FileReader();
      reader.onload = function (evt) { parseTxtContent(evt.target.result); };
      reader.readAsText(file);
    } else if (ext === 'csv') {
      const reader = new FileReader();
      reader.onload = function (evt) { parseCsvContent(evt.target.result); };
      reader.readAsText(file);
    } else if (ext === 'xlsx') {
      const reader = new FileReader();
      reader.onload = function (evt) { parseXlsxContent(evt.target.result); };
      reader.readAsArrayBuffer(file);
    } else {
      AudioEffects.playAlert();
      showToast('Unsupported file type. Please upload CSV, TXT, or Excel XLSX.');
    }
  }

  function parseTxtContent(text) {
    const lines = text.split(/\r?\n/).map(l => l.trim()).filter(l => l.length > 0);
    state.importData.headers = ['Phone Number'];
    state.importData.rows = lines.map(line => [line]);
    setupColumnMapping();
  }

  function parseCsvContent(text) {
    const lines = text.split(/\r?\n/).filter(l => l.trim().length > 0);
    if (lines.length === 0) {
      showToast('CSV file is empty.');
      return;
    }
    const sample = lines[0];
    const delimiter = sample.includes('\t') ? '\t' : (sample.includes(';') ? ';' : ',');

    const parseLine = line => {
      const row = [];
      let inQuotes = false;
      let cur = '';
      for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (c === '"') {
          inQuotes = !inQuotes;
        } else if (c === delimiter && !inQuotes) {
          row.push(cur.trim());
          cur = '';
        } else {
          cur += c;
        }
      }
      row.push(cur.trim());
      return row;
    };

    state.importData.headers = parseLine(lines[0]);
    state.importData.rows = lines.slice(1).map(parseLine);
    setupColumnMapping();
  }

  function parseXlsxContent(buffer) {
    try {
      const textDecoder = new TextDecoder('utf-8');
      const bytes = new Uint8Array(buffer);
      let text = '';
      try { text = textDecoder.decode(bytes); } catch (err) {}

      const phoneRegex = /(?:\+?[0-9]{7,15})/g;
      const matches = text.match(phoneRegex) || [];
      const unique = Array.from(new Set(matches)).filter(m => m.length >= 7 && m.length <= 15);

      if (unique.length > 0) {
        state.importData.headers = ['Phone Number'];
        state.importData.rows = unique.map(num => [num]);
        setupColumnMapping();
        showToast(`Extracted ${unique.length} phone records from Excel file.`);
      } else {
        showToast('Could not extract records from XLSX. Try saving as CSV.');
      }
    } catch (e) {
      showToast('Error parsing XLSX file.');
    }
  }

  function setupColumnMapping() {
    const headers = state.importData.headers;
    const phoneSelect = document.getElementById('map-col-phone');
    const nameSelect = document.getElementById('map-col-name');
    const groupSelect = document.getElementById('map-col-group');
    const consentSelect = document.getElementById('map-col-consent');

    const optionsHtml = headers.map((h, i) => `<option value="${i}">${escapeHtml(h)}</option>`).join('');
    const emptyOption = '<option value="-1">-- None --</option>';

    phoneSelect.innerHTML = optionsHtml;
    nameSelect.innerHTML = emptyOption + optionsHtml;
    groupSelect.innerHTML = emptyOption + optionsHtml;
    consentSelect.innerHTML = emptyOption + optionsHtml;

    headers.forEach((h, i) => {
      const lower = h.toLowerCase();
      if (lower.includes('phone') || lower.includes('mobile') || lower.includes('tel') || lower.includes('number')) phoneSelect.value = i;
      if (lower.includes('name') || lower.includes('recipient') || lower.includes('customer')) nameSelect.value = i;
      if (lower.includes('group') || lower.includes('category')) groupSelect.value = i;
      if (lower.includes('consent') || lower.includes('opt')) consentSelect.value = i;
    });

    document.getElementById('import-mapping-card').classList.remove('hidden');
    runImportValidation();
  }

  window.runImportValidation = function () {
    const phoneIdx = parseInt(document.getElementById('map-col-phone').value, 10);
    const nameIdx = parseInt(document.getElementById('map-col-name').value, 10);
    const groupIdx = parseInt(document.getElementById('map-col-group').value, 10);
    const consentIdx = parseInt(document.getElementById('map-col-consent').value, 10);

    const rows = state.importData.rows;
    const seen = new Set();
    const valid = [];
    let duplicates = 0;
    let invalid = 0;

    rows.forEach(r => {
      const rawPhone = (r[phoneIdx] || '').toString().trim();
      const normalized = rawPhone.replace(/[\s\-\(\)\.]/g, '');

      if (!/^\+?[0-9]{6,16}$/.test(normalized)) {
        invalid++;
        return;
      }
      if (seen.has(normalized)) {
        duplicates++;
        return;
      }
      seen.add(normalized);

      const dispName = nameIdx >= 0 ? (r[nameIdx] || '').toString().trim() : '';
      const groupName = groupIdx >= 0 ? (r[groupIdx] || '').toString().trim() : '';
      let consent = 0;
      if (consentIdx >= 0) {
        const cVal = (r[consentIdx] || '').toString().toLowerCase();
        if (cVal.includes('yes') || cVal === '1' || cVal.includes('true')) consent = 1;
        else if (cVal.includes('optout') || cVal === '-1' || cVal.includes('unsub')) consent = -1;
      }

      valid.push({
        phoneNumber: normalized,
        originalPhoneNumber: rawPhone,
        displayName: dispName,
        groupName: groupName,
        consentStatus: consent,
        consentSource: 'Import'
      });
    });

    state.importData.validRows = valid;
    state.importData.duplicates = duplicates;
    state.importData.invalid = invalid;

    document.getElementById('audit-total').textContent = rows.length;
    document.getElementById('audit-valid').textContent = valid.length;
    document.getElementById('audit-duplicate').textContent = duplicates;
    document.getElementById('audit-invalid').textContent = invalid;

    document.getElementById('btn-save-import').disabled = valid.length === 0;
  };

  window.cancelImport = function () {
    AudioEffects.playClick();
    document.getElementById('import-mapping-card').classList.add('hidden');
    state.importData = { headers: [], rows: [], validRows: [], duplicates: 0, invalid: 0 };
  };

  window.commitImportToDatabase = function () {
    AudioEffects.playClick();
    if (!state.importData.validRows || state.importData.validRows.length === 0) return;

    const listName = document.getElementById('import-list-name').value.trim() || 'Imported Contacts';
    const res = Native.saveRecipientList(listName, 'import', state.importData.validRows);

    if (res.success) {
      AudioEffects.playSuccess();
      showToast(`Imported ${res.savedCount} contacts into '${listName}'!`);
      cancelImport();
      loadRecipientLists();
      loadDashboard();
    } else {
      AudioEffects.playAlert();
      showToast(res.errorMessage || 'Import failed');
    }
  };

  window.loadRecipientLists = function () {
    state.recipientLists = Native.getRecipientLists();
    const container = document.getElementById('recipient-lists-container');
    if (!state.recipientLists || state.recipientLists.length === 0) {
      container.innerHTML = '<div class="empty-state">No recipient lists stored yet.</div>';
      return;
    }

    container.innerHTML = state.recipientLists.map(l => `
      <div style="display:flex; justify-content:space-between; align-items:center; padding:10px 0; border-bottom:1px solid var(--border);">
        <div>
          <strong>${escapeHtml(l.name)}</strong>
          <span style="color:var(--text-muted); font-size:0.75rem; display:block;">
            Source: ${escapeHtml(l.sourceType)} • ${formatTimestamp(l.createdAt)}
          </span>
        </div>
        <div style="display:flex; align-items:center; gap:8px;">
          <span class="badge badge-cyan">${l.memberCount} recipients</span>
          <button class="btn btn-xs btn-outline" onclick="selectListForCampaign(${l.id})">Use</button>
        </div>
      </div>
    `).join('');
  };

  window.selectListForCampaign = function (listId) {
    navigateTo('screen-composer');
    setTimeout(() => {
      const select = document.getElementById('campaign-recipient-list');
      if (select) select.value = listId;
      updateRecipientCountPreview();
    }, 100);
  };

  // ==============================================================
  // SCREEN 5: SUPPRESSION & OPT-OUT LIST (Notable New Function!)
  // ==============================================================
  window.loadOptOutList = function () {
    state.optOutContacts = Native.getOptOutContacts();
    document.getElementById('blacklist-count').textContent = state.optOutContacts.length;

    const tbody = document.getElementById('blacklist-table-body');
    if (!state.optOutContacts || state.optOutContacts.length === 0) {
      tbody.innerHTML = '<tr><td colspan="4" class="text-center">No numbers in suppression list.</td></tr>';
      return;
    }

    tbody.innerHTML = state.optOutContacts.map(c => `
      <tr>
        <td><strong>${escapeHtml(c.phoneNumber)}</strong></td>
        <td>${escapeHtml(c.reason || 'User Opt-Out')}</td>
        <td>${formatTimestamp(c.updatedAt)}</td>
        <td>
          <button class="btn btn-xs btn-outline-danger" onclick="removeOptOut(${c.id})">Unblock</button>
        </td>
      </tr>
    `).join('');
  };

  window.addToBlacklist = function () {
    AudioEffects.playClick();
    const phone = document.getElementById('blacklist-phone-input').value.trim();
    const reason = document.getElementById('blacklist-reason-input').value.trim() || 'Manual Blacklist';

    if (!phone) {
      AudioEffects.playAlert();
      showToast('Enter a phone number to suppress.');
      return;
    }

    const res = Native.addOptOutContact(phone, reason);
    if (res.success) {
      AudioEffects.playSuccess();
      showToast(`Added ${phone} to suppression list.`);
      document.getElementById('blacklist-phone-input').value = '';
      document.getElementById('blacklist-reason-input').value = '';
      loadOptOutList();
    }
  };

  window.removeOptOut = function (contactId) {
    AudioEffects.playClick();
    if (confirm('Remove number from suppression list?')) {
      Native.removeOptOutContact(contactId);
      AudioEffects.playSuccess();
      showToast('Removed from suppression list.');
      loadOptOutList();
    }
  };

  // ==============================================================
  // SCREEN 6: CAMPAIGN COMPOSER
  // ==============================================================
  window.loadComposer = function () {
    state.recipientLists = Native.getRecipientLists();
    const listSelect = document.getElementById('campaign-recipient-list');
    listSelect.innerHTML = state.recipientLists.map(l => `
      <option value="${l.id}">${escapeHtml(l.name)} (${l.memberCount} recipients)</option>
    `).join('');

    state.simCards = Native.getSimCards();
    const simSelect = document.getElementById('campaign-sim-select');
    simSelect.innerHTML = state.simCards.map(s => `
      <option value="${s.subscriptionId}">${escapeHtml(s.displayName)} (${escapeHtml(s.carrierName || 'Carrier')})</option>
    `).join('');

    state.templates = Native.getTemplates();
    const tmplSelect = document.getElementById('campaign-template-select');
    tmplSelect.innerHTML = '<option value="-1">-- Custom / Choose Template --</option>' +
      state.templates.map(t => `<option value="${t.id}">${escapeHtml(t.name)}</option>`).join('');

    const defaultInterval = state.preferences.sending_interval_ms || 5000;
    document.getElementById('campaign-interval-slider').value = defaultInterval;
    updateIntervalSlider(defaultInterval);

    updateMessageMetrics();
  };

  window.applySelectedTemplate = function () {
    AudioEffects.playClick();
    const tmplId = parseInt(document.getElementById('campaign-template-select').value, 10);
    if (tmplId <= 0) return;
    const tmpl = state.templates.find(t => t.id === tmplId);
    if (tmpl) {
      document.getElementById('campaign-message-text').value = tmpl.content;
      updateMessageMetrics();
    }
  };

  window.insertPlaceholder = function (ph) {
    AudioEffects.playClick();
    const textarea = document.getElementById('campaign-message-text');
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const text = textarea.value;
    textarea.value = text.substring(0, start) + ph + text.substring(end);
    textarea.focus();
    textarea.selectionStart = textarea.selectionEnd = start + ph.length;
    updateMessageMetrics();
  };

  window.updateMessageMetrics = function () {
    const text = document.getElementById('campaign-message-text').value;
    const charCount = text.length;
    document.getElementById('char-count').textContent = charCount;

    const isUnicode = /[^\u0000-\u007F]/.test(text);
    const encoding = isUnicode ? 'UCS-2' : 'GSM-7';
    document.getElementById('encoding-type').textContent = encoding;

    let segments = 1;
    if (isUnicode) {
      segments = charCount <= 70 ? 1 : Math.ceil(charCount / 67);
    } else {
      segments = charCount <= 160 ? 1 : Math.ceil(charCount / 153);
    }
    document.getElementById('segment-count').textContent = segments;

    // Sample contact switcher preview
    const sampleContactName = document.getElementById('sample-contact-picker')?.value || 'Alice Johnson';
    const preview = text
      .replace(/\{name\}/gi, sampleContactName)
      .replace(/\{date\}/gi, new Date().toLocaleDateString())
      .replace(/\{time\}/gi, new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }));

    document.getElementById('sms-live-preview').textContent = preview || `Hello ${sampleContactName}, your appointment is confirmed...`;
  };

  window.updateIntervalSlider = function (val) {
    const sec = (val / 1000).toFixed(1);
    document.getElementById('interval-display').textContent = `${sec} seconds`;
  };

  window.clearComposer = function () {
    AudioEffects.playClick();
    document.getElementById('campaign-name').value = 'Campaign - ' + new Date().toLocaleDateString();
    document.getElementById('campaign-message-text').value = '';
    updateMessageMetrics();
  };

  window.showSaveTemplateModal = function () {
    AudioEffects.playClick();
    const text = document.getElementById('campaign-message-text').value.trim();
    if (!text) {
      AudioEffects.playAlert();
      showToast('Enter message text first to save as template.');
      return;
    }
    document.getElementById('template-name-input').value = document.getElementById('campaign-name').value.trim() || 'New Template';
    document.getElementById('modal-save-template').classList.remove('hidden');
  };

  window.confirmSaveTemplate = function () {
    AudioEffects.playClick();
    const name = document.getElementById('template-name-input').value.trim();
    const content = document.getElementById('campaign-message-text').value.trim();
    if (!name || !content) {
      showToast('Name and content cannot be empty.');
      return;
    }
    const res = Native.saveTemplate(0, name, content);
    if (res.success) {
      AudioEffects.playSuccess();
      showToast('Template saved successfully!');
      closeModal('modal-save-template');
      loadComposer();
    }
  };

  window.openStartCampaignConfirmation = function () {
    AudioEffects.playClick();
    const name = document.getElementById('campaign-name').value.trim();
    const listId = parseInt(document.getElementById('campaign-recipient-list').value, 10);
    const text = document.getElementById('campaign-message-text').value.trim();
    const intervalMs = parseInt(document.getElementById('campaign-interval-slider').value, 10);
    const simSubId = parseInt(document.getElementById('campaign-sim-select').value, 10);

    if (!name) { AudioEffects.playAlert(); showToast('Please enter a campaign title.'); return; }
    if (!listId || isNaN(listId)) { AudioEffects.playAlert(); showToast('Please select a recipient list.'); return; }
    if (!text) { AudioEffects.playAlert(); showToast('Message content cannot be empty.'); return; }

    const selectedList = state.recipientLists.find(l => l.id === listId);
    const recipientCount = selectedList ? selectedList.memberCount : 0;
    if (recipientCount === 0) { AudioEffects.playAlert(); showToast('Selected list has 0 recipients.'); return; }

    const sim = state.simCards.find(s => s.subscriptionId === simSubId);
    const simName = sim ? `${sim.displayName} (${sim.carrierName})` : 'Default SIM';
    const intervalSec = (intervalMs / 1000).toFixed(1);
    const durationMin = Math.ceil((recipientCount * intervalMs) / 60000);

    document.getElementById('modal-conf-title').textContent = name;
    document.getElementById('modal-conf-recipients').textContent = `${recipientCount} contacts`;
    document.getElementById('modal-conf-sim').textContent = simName;
    document.getElementById('modal-conf-interval').textContent = `${intervalSec} seconds per SMS`;
    document.getElementById('modal-conf-duration').textContent = `Approx. ${durationMin} minute(s)`;
    document.getElementById('modal-conf-preview').textContent = text.replace(/\{name\}/gi, 'Alice Johnson');

    const ackCheckbox = document.getElementById('modal-conf-ack');
    ackCheckbox.checked = false;
    const confirmBtn = document.getElementById('btn-modal-confirm-start');
    confirmBtn.disabled = true;

    ackCheckbox.onchange = function () {
      confirmBtn.disabled = !ackCheckbox.checked;
    };

    document.getElementById('modal-start-confirm').classList.remove('hidden');
  };

  window.confirmLaunchCampaign = function () {
    AudioEffects.playClick();
    closeModal('modal-start-confirm');

    const name = document.getElementById('campaign-name').value.trim();
    const listId = parseInt(document.getElementById('campaign-recipient-list').value, 10);
    const text = document.getElementById('campaign-message-text').value.trim();
    const intervalMs = parseInt(document.getElementById('campaign-interval-slider').value, 10);
    const simSubId = parseInt(document.getElementById('campaign-sim-select').value, 10);
    const allowUnverified = document.getElementById('allow-unverified-consent').checked;

    const createRes = Native.createCampaign(name, text, listId, intervalMs, simSubId, allowUnverified);
    if (!createRes.success) {
      AudioEffects.playAlert();
      showToast(createRes.errorMessage || 'Failed to create campaign');
      return;
    }

    const campaignId = createRes.campaignId;
    const startRes = Native.startCampaign(campaignId);
    if (startRes.success) {
      AudioEffects.playSuccess();
      showToast('Campaign broadcast started!');
      navigateTo('screen-active');
    } else {
      AudioEffects.playAlert();
      showToast(startRes.errorMessage || 'Failed to start queue service');
    }
  };

  // ==============================================================
  // SCREEN 7: ACTIVE MONITOR & SVG COUNTDOWN RING ANIMATION
  // ==============================================================
  function startActiveMonitorPoll() {
    if (state.activePollInterval) clearInterval(state.activePollInterval);
    state.activePollInterval = setInterval(() => {
      pollActiveCampaign();
    }, 1000);
  }

  function pollActiveCampaign() {
    const actState = Native.getActiveCampaignState();
    const topPill = document.getElementById('topbar-live-indicator');
    const navDot = document.getElementById('nav-active-dot');
    const navPill = document.getElementById('nav-active-pill');

    const isActive = actState && actState.activeCampaignId > 0 && (actState.status === 'Processing' || actState.status === 'Paused');

    if (actState && actState.status === 'Completed' && actState.activeCampaignId > 0) {
      if (state.lastCompletedCampaignId !== actState.activeCampaignId) {
        state.lastCompletedCampaignId = actState.activeCampaignId;
        AudioEffects.playSuccess();
        if (state.preferences.auto_email_report && state.preferences.report_email) {
          setTimeout(() => {
            Native.sendCampaignEmailReport(actState.activeCampaignId, state.preferences.report_email);
            showToast('Auto-sending blast report to ' + state.preferences.report_email);
          }, 800);
        }
      }
    }

    if (!isActive) {
      topPill.classList.add('hidden');
      navDot.classList.add('hidden');
      navPill.classList.add('hidden');
      if (state.currentScreen === 'screen-active') {
        document.getElementById('active-status-badge').textContent = actState ? actState.status : 'IDLE';
        document.getElementById('active-countdown-num').textContent = '0s';
        updateSvgCountdownRing(0, 5);
      }
      return;
    }

    // Active campaign in progress
    topPill.classList.remove('hidden');
    navDot.classList.remove('hidden');
    navPill.classList.remove('hidden');

    if (state.currentScreen === 'screen-active') {
      document.getElementById('active-status-badge').textContent = actState.status;
      document.getElementById('active-campaign-title').textContent = actState.campaignName || 'Campaign';

      const countdownSec = actState.countdownSeconds || 0;
      document.getElementById('active-countdown-num').textContent = `${countdownSec}s`;

      const intervalSec = Math.max(1, Math.round((actState.configuredIntervalMs || 5000) / 1000));
      updateSvgCountdownRing(countdownSec, intervalSec);

      const processed = actState.processedCount || 0;
      const total = actState.totalRecipients || 0;
      const pct = total > 0 ? Math.round((processed / total) * 100) : 0;

      document.getElementById('active-processed-ratio').textContent = `${processed} / ${total}`;
      document.getElementById('active-progress-percent').textContent = `${pct}%`;
      document.getElementById('active-progress-bar').style.width = `${pct}%`;

      const remaining = Math.max(0, total - processed);
      const estSec = Math.round((remaining * (actState.configuredIntervalMs || 5000)) / 1000);
      document.getElementById('active-time-estimate').textContent = `${Math.ceil(estSec / 60)} min (${estSec}s)`;
      document.getElementById('active-interval-val').textContent = `${((actState.configuredIntervalMs || 5000) / 1000).toFixed(1)}s`;

      const currentNum = actState.destinationNumber;
      document.getElementById('active-current-num').textContent = currentNum ? (state.maskNumbers ? maskNumber(currentNum) : currentNum) : 'None';
      document.getElementById('active-submitted-count').textContent = actState.submittedCount || 0;
      document.getElementById('active-delivered-count').textContent = actState.deliveredCount || 0;
      document.getElementById('active-failed-count').textContent = actState.failedCount || 0;

      const isPaused = actState.status === 'Paused';
      document.getElementById('btn-pause-campaign').classList.toggle('hidden', isPaused);
      document.getElementById('btn-resume-campaign').classList.toggle('hidden', !isPaused);

      const logs = Native.getLiveLogs();
      renderLiveLogs(logs);
    }
  }

  function updateSvgCountdownRing(secondsRemaining, totalSeconds) {
    const circle = document.getElementById('countdown-meter-circle');
    if (!circle) return;
    const circumference = 213.628; // 2 * PI * 34
    const ratio = Math.max(0, Math.min(1, secondsRemaining / totalSeconds));
    const offset = circumference * (1 - ratio);
    circle.style.strokeDashoffset = offset;
  }

  function renderLiveLogs(logs) {
    const container = document.getElementById('live-log-container');
    if (!logs || logs.length === 0) return;
    container.innerHTML = logs.map(l => `
      <div class="log-entry ${l.type || 'INFO'}">
        <span style="opacity:0.6;">${formatTimeOnly(l.timestamp)}</span> — ${escapeHtml(l.message)}
      </div>
    `).join('');
  }

  window.pauseActiveCampaign = function () {
    AudioEffects.playClick();
    Native.pauseCampaign();
    showToast('Broadcast queue paused.');
  };

  window.resumeActiveCampaign = function () {
    AudioEffects.playClick();
    Native.resumeCampaign();
    showToast('Broadcast queue resumed.');
  };

  window.stopActiveCampaign = function () {
    AudioEffects.playClick();
    if (confirm('Cancel and stop remaining broadcasts for this campaign?')) {
      Native.stopCampaign();
      AudioEffects.playAlert();
      showToast('Campaign broadcast stopped.');
    }
  };

  // ==============================================================
  // SCREEN 8: CAMPAIGN HISTORY
  // ==============================================================
  window.loadCampaignHistory = function () {
    const campaigns = Native.getCampaigns(50, 0);
    const container = document.getElementById('history-list-container');
    if (!campaigns || campaigns.length === 0) {
      container.innerHTML = '<div class="empty-state">No broadcast history records found.</div>';
      return;
    }

    container.innerHTML = campaigns.map(c => `
      <div style="padding:14px; border-bottom:1px solid var(--border); font-size:0.86rem;">
        <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:8px;">
          <div>
            <span class="status-tag ${c.status}">${c.status}</span>
            <strong style="margin-left:6px;">${escapeHtml(c.name)}</strong>
            <div style="color:var(--text-muted); font-size:0.75rem; margin-top:4px;">
              Created: ${formatTimestamp(c.createdAt)} • Target: ${escapeHtml(c.listName || 'Direct')}
            </div>
          </div>
          <div style="display:flex; gap:6px; flex-shrink:0;">
            <button class="btn btn-xs btn-outline-cyan" onclick="openEmailReportModal(${c.id})">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"></path><polyline points="22,6 12,13 2,6"></polyline></svg>
              Email Report
            </button>
            <button class="btn btn-xs btn-outline" onclick="inspectCampaignJobs(${c.id})">Inspect</button>
          </div>
        </div>
        <div style="display:flex; gap:16px; margin-top:10px; font-size:0.78rem;">
          <span>Total: <strong>${c.totalRecipients}</strong></span>
          <span class="text-blue">Submitted: <strong>${c.submittedCount || 0}</strong></span>
          <span class="text-green">Delivered: <strong>${c.deliveredCount || 0}</strong></span>
          <span class="text-red">Failed: <strong>${c.failedCount || 0}</strong></span>
        </div>
      </div>
    `).join('');
  };

  window.inspectCampaignJobs = function (campaignId) {
    navigateTo('screen-reports');
    setTimeout(() => {
      const select = document.getElementById('report-filter-campaign');
      if (select) select.value = campaignId;
      loadReports();
    }, 150);
  };

  // ==============================================================
  // SCREEN 9: REPORTS & AUDIT LOGS
  // ==============================================================
  window.loadReports = function () {
    const campaigns = Native.getCampaigns(100, 0);
    const campSelect = document.getElementById('report-filter-campaign');
    const curVal = campSelect.value;
    campSelect.innerHTML = '<option value="0">All Campaigns</option>' +
      campaigns.map(c => `<option value="${c.id}">${escapeHtml(c.name)}</option>`).join('');
    if (curVal) campSelect.value = curVal;

    const campaignId = parseInt(campSelect.value, 10) || 0;
    const statusFilter = document.getElementById('report-filter-status').value || 'ALL';
    const dateFilter = document.getElementById('report-filter-date').value || 'ALL';

    let startDate = 0;
    const now = Date.now();
    if (dateFilter === 'TODAY') {
      const d = new Date(); d.setHours(0,0,0,0);
      startDate = d.getTime();
    } else if (dateFilter === '7DAYS') {
      startDate = now - 7 * 86400000;
    } else if (dateFilter === '30DAYS') {
      startDate = now - 30 * 86400000;
    }

    const data = Native.getReportsData(startDate, 0, campaignId);
    document.getElementById('rep-success-rate').textContent = `${data.successRate || '0.0'}%`;
    document.getElementById('rep-failure-rate').textContent = `${data.failureRate || '0.0'}%`;
    document.getElementById('rep-delivered').textContent = data.delivered || 0;
    document.getElementById('rep-total').textContent = data.totalRecipients || 0;

    const jobs = Native.getMessageJobs(campaignId, statusFilter, 100, 0);
    renderReportJobs(jobs);
  };

  function renderReportJobs(jobs) {
    const tbody = document.getElementById('report-jobs-body');
    if (!jobs || jobs.length === 0) {
      tbody.innerHTML = '<tr><td colspan="8" class="text-center">No message jobs match the filter.</td></tr>';
      return;
    }

    tbody.innerHTML = jobs.map(j => {
      const phone = state.maskNumbers ? maskNumber(j.destinationNumber) : j.destinationNumber;
      return `
        <tr>
          <td>#${j.id}</td>
          <td><strong>${escapeHtml(phone)}</strong></td>
          <td>${escapeHtml(j.campaignName || '')}</td>
          <td title="${escapeHtml(j.renderedMessage)}">${escapeHtml(truncate(j.renderedMessage, 28))}</td>
          <td><span class="status-tag ${j.status}">${j.status}</span></td>
          <td>${j.submittedAt ? formatTimeOnly(j.submittedAt) : '-'}</td>
          <td>${j.deliveredAt ? formatTimeOnly(j.deliveredAt) : '-'}</td>
          <td class="text-red">${escapeHtml(j.errorMessage || '-')}</td>
        </tr>
      `;
    }).join('');
  }

  window.toggleNumberMasking = function () {
    AudioEffects.playClick();
    state.maskNumbers = !state.maskNumbers;
    document.getElementById('btn-toggle-mask').textContent = state.maskNumbers ? 'Show Full Numbers' : 'Mask Numbers';
    loadReports();
  };

  window.exportReportsCsv = function () {
    AudioEffects.playClick();
    const campaignId = parseInt(document.getElementById('report-filter-campaign').value, 10) || 0;
    const statusFilter = document.getElementById('report-filter-status').value || 'ALL';
    const jobs = Native.getMessageJobs(campaignId, statusFilter, 5000, 0);

    if (!jobs || jobs.length === 0) {
      AudioEffects.playAlert();
      showToast('No jobs to export.');
      return;
    }

    const headers = ['job_id', 'campaign_name', 'destination_number', 'rendered_message', 'status', 'submitted_at', 'delivered_at', 'error_code', 'error_message', 'correlation_id'];
    const rows = jobs.map(j => [
      j.id,
      `"${(j.campaignName || '').replace(/"/g, '""')}"`,
      `"${j.destinationNumber}"`,
      `"${j.renderedMessage.replace(/"/g, '""')}"`,
      `"${j.status}"`,
      j.submittedAt || '',
      j.deliveredAt || '',
      j.errorCode || 0,
      `"${(j.errorMessage || '').replace(/"/g, '""')}"`,
      `"${j.correlationId || ''}"`
    ]);

    const csvContent = headers.join(',') + '\n' + rows.map(r => r.join(',')).join('\n');
    downloadBlob(csvContent, `sms_audit_report_${Date.now()}.csv`, 'text/csv');
    AudioEffects.playSuccess();
    showToast('Exported audit log CSV.');
  };

  // ==============================================================
  // SCREEN 10: SETTINGS
  // ==============================================================
  window.loadSettings = function () {
    state.preferences = Native.getPreferences();
    document.getElementById('pref-interval').value = state.preferences.sending_interval_ms || 5000;
    document.getElementById('pref-max-campaign').value = state.preferences.max_campaign_size || 500;
    document.getElementById('pref-default-prefix').value = state.preferences.default_prefix || '0992438';
    document.getElementById('pref-show-countdown').checked = state.preferences.show_countdown !== false;
    document.getElementById('pref-enable-notifications').checked = state.preferences.notifications_enabled !== false;
    document.getElementById('pref-require-confirm').checked = state.preferences.require_confirmation !== false;
    document.getElementById('pref-mask-numbers').checked = state.preferences.mask_numbers_by_default !== false;
    document.getElementById('pref-theme').value = state.preferences.theme_preference || 'system';

    const emailEl = document.getElementById('pref-report-email');
    if (emailEl) emailEl.value = state.preferences.report_email || '';

    const autoEmailEl = document.getElementById('pref-auto-email-report');
    if (autoEmailEl) autoEmailEl.checked = !!state.preferences.auto_email_report;

    const compatEl = document.getElementById('pref-compat-mode');
    if (compatEl) compatEl.checked = state.preferences.compatibility_mode !== false;

    const simEl = document.getElementById('pref-sim-mode');
    if (simEl) simEl.checked = !!state.preferences.simulation_mode;

    applyTheme(state.preferences.theme_preference);
  };

  window.saveSettings = function () {
    AudioEffects.playClick();
    const emailVal = document.getElementById('pref-report-email') ? document.getElementById('pref-report-email').value.trim() : '';
    const autoEmailVal = document.getElementById('pref-auto-email-report') ? document.getElementById('pref-auto-email-report').checked : false;
    const compatVal = document.getElementById('pref-compat-mode') ? document.getElementById('pref-compat-mode').checked : true;
    const simVal = document.getElementById('pref-sim-mode') ? document.getElementById('pref-sim-mode').checked : false;

    const p = {
      sending_interval_ms: parseInt(document.getElementById('pref-interval').value, 10) || 5000,
      max_campaign_size: parseInt(document.getElementById('pref-max-campaign').value, 10) || 500,
      default_prefix: document.getElementById('pref-default-prefix').value.trim() || '0992438',
      show_countdown: document.getElementById('pref-show-countdown').checked,
      notifications_enabled: document.getElementById('pref-enable-notifications').checked,
      require_confirmation: document.getElementById('pref-require-confirm').checked,
      mask_numbers_by_default: document.getElementById('pref-mask-numbers').checked,
      theme_preference: document.getElementById('pref-theme').value,
      report_email: emailVal,
      auto_email_report: autoEmailVal,
      compatibility_mode: compatVal,
      simulation_mode: simVal
    };

    Native.savePreferences(p);
    state.preferences = p;
    applyTheme(p.theme_preference);
    AudioEffects.playSuccess();
    showToast('Preferences saved successfully!');
  };

  // ==============================================================
  // EMAIL REPORT DISPATCH & ACR SERVICES FORMATTING
  // ==============================================================
  state.currentReportCampaignId = 0;

  window.openEmailReportModal = function (campaignId) {
    AudioEffects.playClick();
    let cid = campaignId;
    if (!cid || cid <= 0) {
      const campSelect = document.getElementById('report-filter-campaign');
      if (campSelect && parseInt(campSelect.value, 10) > 0) {
        cid = parseInt(campSelect.value, 10);
      } else if (state.activeCampaignId && state.activeCampaignId > 0) {
        cid = state.activeCampaignId;
      } else {
        const campaigns = Native.getCampaigns(1, 0);
        if (campaigns && campaigns.length > 0) {
          cid = campaigns[0].id;
        }
      }
    }

    if (!cid || cid <= 0) {
      AudioEffects.playAlert();
      showToast('No campaign available to report. Please create and run a campaign first.');
      return;
    }

    state.currentReportCampaignId = cid;
    const reportText = Native.getFormattedCampaignReport(cid);
    const previewEl = document.getElementById('email-report-preview');
    if (previewEl) previewEl.value = reportText;

    const emailInput = document.getElementById('email-report-target');
    if (emailInput) {
      emailInput.value = state.preferences.report_email || '';
    }

    openModal('modal-email-report');
  };

  window.sendReportForCurrentCampaign = function () {
    openEmailReportModal(state.activeCampaignId || 0);
  };

  window.sendReportForCampaign = function (campaignId) {
    openEmailReportModal(campaignId);
  };

  window.confirmDispatchEmailReport = function () {
    AudioEffects.playClick();
    const emailEl = document.getElementById('email-report-target');
    const email = emailEl ? emailEl.value.trim() : '';

    if (!email) {
      AudioEffects.playAlert();
      showToast('Please input an email address to send report.');
      if (emailEl) emailEl.focus();
      return;
    }

    // Save as preferred report email
    state.preferences.report_email = email;
    Native.savePreferences({ report_email: email });

    showToast('Sending report via Gmail SMTP...');
    const reportText = document.getElementById('email-report-preview').value;
    const res = Native.sendCampaignEmailReport(state.currentReportCampaignId, email);

    if (res && res.success) {
      AudioEffects.playSuccess();
      showToast('Report sent to ' + email + ' via Gmail SMTP! ✅');
      closeModal('modal-email-report');
    } else {
      AudioEffects.playAlert();
      const err = (res && res.errorMessage) ? res.errorMessage : 'Failed to send email via SMTP.';
      showToast('SMTP Error: ' + err);
    }
  };

  window.testSmtpConnection = function () {
    AudioEffects.playClick();
    const emailInput = document.getElementById('pref-report-email');
    const email = emailInput ? emailInput.value.trim() : (state.preferences.report_email || '');

    if (!email) {
      AudioEffects.playAlert();
      showToast('Please enter an email address in the field above to test delivery.');
      if (emailInput) emailInput.focus();
      return;
    }

    showToast('Testing Gmail SMTP connection...');
    setTimeout(() => {
      const res = Native.testSmtpConnection(email);
      if (res && res.success) {
        AudioEffects.playSuccess();
        showToast('Gmail SMTP test succeeded! Email delivered to ' + email + ' ✅');
      } else {
        AudioEffects.playAlert();
        const err = (res && res.errorMessage) ? res.errorMessage : 'Connection test failed';
        showToast('SMTP Test Error: ' + err);
      }
    }, 50);
  };

  window.copyReportText = function () {
    AudioEffects.playClick();
    const txt = document.getElementById('email-report-preview').value;
    if (!txt) return;

    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(txt).then(() => {
        AudioEffects.playSuccess();
        showToast('Report copied to clipboard!');
      }).catch(() => fallbackCopyText(txt));
    } else {
      fallbackCopyText(txt);
    }
  };

  function fallbackCopyText(text) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      AudioEffects.playSuccess();
      showToast('Report copied to clipboard!');
    } catch (e) {
      showToast('Copy failed.');
    }
    document.body.removeChild(ta);
  }

  window.downloadReportTxt = function () {
    AudioEffects.playClick();
    const txt = document.getElementById('email-report-preview').value;
    if (!txt) return;
    const filename = `sms_blast_report_${state.currentReportCampaignId || Date.now()}.txt`;
    downloadBlob(txt, filename, 'text/plain');
    AudioEffects.playSuccess();
    showToast('Downloaded ' + filename);
  };

  window.resetSettingsToDefaults = function () {
    AudioEffects.playClick();
    if (confirm('Reset all application preferences to safe factory defaults?')) {
      Native.resetPreferences();
      loadSettings();
      AudioEffects.playSuccess();
      showToast('Settings reset to safe defaults.');
    }
  };

  window.applyTheme = function (theme) {
    if (theme === 'dark') {
      document.documentElement.setAttribute('data-theme', 'dark');
    } else if (theme === 'light') {
      document.documentElement.setAttribute('data-theme', 'light');
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
  };

  window.confirmClearData = function (type) {
    AudioEffects.playAlert();
    const msg = type === 'all'
      ? 'DANGER: Permanently delete ALL campaigns, recipients, messages, and telemetry from local SQLite?'
      : (type === 'history' ? 'Delete all broadcast logs and campaign records?' : 'Delete all imported contacts and lists?');

    if (confirm(msg)) {
      Native.clearLocalData(type);
      AudioEffects.playSuccess();
      showToast('Local database purged.');
      loadDashboard();
      loadRecipientLists();
      loadCampaignHistory();
      loadOptOutList();
    }
  };

  // ==============================================================
  // HELPER UTILITIES & ANIMATIONS
  // ==============================================================
  function animateNumber(elementId, targetValue, duration = 600) {
    const el = document.getElementById(elementId);
    if (!el) return;
    const startVal = parseInt(el.textContent.replace(/[^0-9]/g, ''), 10) || 0;
    const endVal = parseInt(targetValue, 10) || 0;
    if (startVal === endVal) {
      el.textContent = endVal;
      return;
    }
    const startTime = performance.now();
    function step(currentTime) {
      const elapsed = currentTime - startTime;
      const progress = Math.min(elapsed / duration, 1);
      const current = Math.floor(startVal + (endVal - startVal) * progress);
      el.textContent = current;
      if (progress < 1) {
        requestAnimationFrame(step);
      } else {
        el.textContent = endVal;
      }
    }
    requestAnimationFrame(step);
  }

  function formatTimestamp(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function formatTimeOnly(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  function maskNumber(phone) {
    if (!phone || phone.length <= 5) return phone;
    const p = phone.slice(0, 3);
    const s = phone.slice(-2);
    return p + '***' + s;
  }

  function truncate(str, len) {
    if (!str) return '';
    return str.length > len ? str.slice(0, len) + '...' : str;
  }

  function escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function downloadBlob(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  window.closeModal = function (modalId) {
    AudioEffects.playClick();
    const el = document.getElementById(modalId);
    if (el) el.classList.add('hidden');
  };

  window.showToast = function (msg) {
    const t = document.getElementById('toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.remove('hidden');
    t.style.opacity = '1';
    setTimeout(() => {
      t.style.opacity = '0';
      setTimeout(() => t.classList.add('hidden'), 200);
    }, 2800);
  };

})();
