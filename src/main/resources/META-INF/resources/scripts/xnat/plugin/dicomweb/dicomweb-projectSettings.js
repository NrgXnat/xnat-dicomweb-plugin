/**
 * dicomweb-projectSettings.js
 * Project-level DICOMweb settings.
 * Allows project owners to control whether this project is included in site-wide DICOMweb queries.
 */
(function(){
    'use strict';

    var _csrfToken = window.csrfToken || '';

    function fetchJson(url, opts) {
        opts = opts || {};
        opts.credentials = 'same-origin';
        opts.headers = Object.assign({
            'Accept': 'application/json',
            'Content-Type': 'application/json'
        }, opts.headers || {});
        if (_csrfToken) opts.headers['XNAT-CSRF'] = _csrfToken;
        return fetch(url, opts).then(function(r) {
            if (!r.ok) {
                if (r.status === 404) return null;
                throw new Error('HTTP ' + r.status);
            }
            if (r.status === 204) return null;
            var ct = r.headers.get('content-type') || '';
            if (ct.indexOf('json') === -1) return null;
            return r.json();
        });
    }

    function getProjectId() {
        if (typeof XNAT !== 'undefined' && XNAT.data && XNAT.data.context && XNAT.data.context.project) {
            return XNAT.data.context.project;
        }
        var match = window.location.pathname.match(/\/project\/([^/]+)/);
        return match ? match[1] : null;
    }

    function init() {
        var container = document.getElementById('dicomweb-project-settings');
        if (!container) return;

        var projectId = getProjectId();
        if (!projectId) {
            container.innerHTML = '<p>Could not determine project ID.</p>';
            return;
        }

        container.innerHTML = '<p>Loading DICOMweb settings...</p>';

        var sitePrefsUrl = XNAT.url.rootUrl('/xapi/dicomweb/prefs');
        var projectConfigUrl = XNAT.url.rootUrl('/xapi/dicomweb/projects/' + projectId + '/config/site-wide');

        Promise.all([
            fetchJson(sitePrefsUrl),
            fetchJson(projectConfigUrl)
        ]).then(function(results) {
            var sitePrefs = results[0] || {};
            var projectConfig = results[1] || {};

            var siteWideEnabled = sitePrefs.siteWideEnabled === true;
            var filterMode = sitePrefs.filterMode || 'blacklist';
            var projectList = (sitePrefs.projectList || '').split(',').map(function(s) { return s.trim(); }).filter(Boolean);
            var excludedByProject = projectConfig.excludeFromSiteWide === true;

            // Determine if this project is currently included in site-wide queries
            var includedBySiteFilter;
            if (filterMode === 'whitelist') {
                includedBySiteFilter = projectList.indexOf(projectId) !== -1;
            } else {
                includedBySiteFilter = projectList.indexOf(projectId) === -1;
            }

            var effectivelyIncluded = siteWideEnabled && includedBySiteFilter && !excludedByProject;

            var html = '';

            if (!siteWideEnabled) {
                html += '<div style="background:#f0f0f0;padding:12px;border-radius:4px;margin-bottom:16px;">' +
                        '<strong>Site-wide DICOMweb queries are disabled.</strong> ' +
                        'The settings below will take effect if a site administrator enables site-wide queries.' +
                        '</div>';
            } else {
                if (!includedBySiteFilter) {
                    html += '<div style="background:#fff3cd;padding:12px;border:1px solid #ffc107;border-radius:4px;margin-bottom:16px;">' +
                            '<strong>Note:</strong> This project is currently ' +
                            (filterMode === 'whitelist' ? 'not in the site-wide whitelist.' : 'in the site-wide blacklist.') +
                            ' The site administrator controls this setting.' +
                            '</div>';
                } else {
                    html += '<div style="background:' + (effectivelyIncluded ? '#d4edda' : '#f8d7da') +
                            ';padding:12px;border:1px solid ' + (effectivelyIncluded ? '#c3e6cb' : '#f5c6cb') +
                            ';border-radius:4px;margin-bottom:16px;">' +
                            'This project is currently <strong>' +
                            (effectivelyIncluded ? 'included in' : 'excluded from') +
                            '</strong> site-wide DICOMweb queries.' +
                            '</div>';
                }
            }

            html += '<div style="margin-top:12px;">' +
                    '<label style="display:flex;align-items:center;gap:8px;cursor:pointer;">' +
                    '<input type="checkbox" id="dicomweb-exclude-from-sitewide"' +
                    (excludedByProject ? ' checked' : '') + '>' +
                    ' Exclude this project from site-wide DICOMweb queries' +
                    '</label>' +
                    '<p style="margin:8px 0 0 26px;color:#666;font-size:0.9em;">' +
                    'When checked, this project\'s data will not appear in site-wide DICOMweb searches or retrievals, ' +
                    'regardless of the site-level filter settings. ' +
                    'Project-scoped endpoints (/xapi/dicomweb/projects/' + projectId + '/...) are not affected.' +
                    '</p></div>';

            html += '<div style="margin-top:16px;">' +
                    '<button id="dicomweb-save-project-settings" class="btn btn-primary btn-sm">Save</button>' +
                    '<span id="dicomweb-save-status" style="margin-left:12px;"></span>' +
                    '</div>';

            container.innerHTML = html;

            document.getElementById('dicomweb-save-project-settings').addEventListener('click', function() {
                var checkbox = document.getElementById('dicomweb-exclude-from-sitewide');
                var status = document.getElementById('dicomweb-save-status');
                status.textContent = 'Saving...';
                status.style.color = '#666';

                fetchJson(projectConfigUrl, {
                    method: 'PUT',
                    body: JSON.stringify({ excludeFromSiteWide: checkbox.checked })
                }).then(function() {
                    status.textContent = 'Saved.';
                    status.style.color = '#28a745';
                    setTimeout(function() { status.textContent = ''; }, 3000);
                }).catch(function(err) {
                    status.textContent = 'Error: ' + err.message;
                    status.style.color = '#dc3545';
                });
            });
        }).catch(function(err) {
            container.innerHTML = '<p style="color:red;">Error loading settings: ' + err.message + '</p>';
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
