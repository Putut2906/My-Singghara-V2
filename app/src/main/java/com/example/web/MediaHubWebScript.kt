package com.example.web

/**
 * Skrip web Web Connect untuk pustaka media HP: Now Playing bar global dengan lirik,
 * browser berkas HP, dan halaman pemutar video.
 *
 * Semua JavaScript memakai concatenation biasa (tanpa template literal) agar tidak
 * perlu melepaskan tanda dollar di dalam raw string Kotlin.
 */
object MediaHubWebScript {

    fun getScript(dol: String): String {
        return """
        // =========================================================================
        // --- PUSTAKA MEDIA HP + NOW PLAYING GLOBAL (100% OFFLINE) ---
        // =========================================================================

        window.SinggharaMedia = window.SinggharaMedia || {};

        window.SinggharaMedia.sessionToken = function() {
            try {
                if (window.activeAuthToken) return window.activeAuthToken;
                return sessionStorage.getItem('singghara_auth_token') || localStorage.getItem('singghara_auth_token') || '';
            } catch (e) {
                return '';
            }
        };

        window.SinggharaMedia.withToken = function(url) {
            var token = window.SinggharaMedia.sessionToken();
            if (!token) return url;
            return url + (url.indexOf('?') >= 0 ? '&' : '?') + 't=' + encodeURIComponent(token);
        };

        window.SinggharaMedia.getJson = async function(url) {
            var response = await fetch(window.SinggharaMedia.withToken(url));
            if (!response.ok) {
                var error = new Error('HTTP ' + response.status);
                error.status = response.status;
                throw error;
            }
            return await response.json();
        };

        window.SinggharaMedia.loadRoots = async function() {
            return await window.SinggharaMedia.getJson('/api/storage/roots');
        };

        window.SinggharaMedia.list = async function(root, dir, kind, query) {
            var url = '/api/storage/list?root=' + encodeURIComponent(root || 'music') +
                    '&dir=' + encodeURIComponent(dir || '') +
                    '&kind=' + encodeURIComponent(kind || 'all') +
                    '&q=' + encodeURIComponent(query || '');
            return await window.SinggharaMedia.getJson(url);
        };

        window.SinggharaMedia.streamUrl = function(item, asDownload) {
            var url = '/api/storage/stream?root=' + encodeURIComponent(item.root || 'music') +
                    '&path=' + encodeURIComponent(item.path || '');
            if (asDownload) url += '&download=1';
            return window.SinggharaMedia.withToken(url);
        };

        window.SinggharaMedia.loadLyrics = async function(track) {
            if (!track || !track.phonePath) return '';
            try {
                var data = await window.SinggharaMedia.getJson('/api/storage/lyrics?root=' +
                        encodeURIComponent(track.root || 'music') + '&path=' + encodeURIComponent(track.phonePath));
                var text = (data && data.lyrics) ? data.lyrics : '';
                if (text) window.musicManualLyrics[track.id] = text;
                if (window.refreshGlobalLyricsView) window.refreshGlobalLyricsView();
                return text;
            } catch (e) {
                return '';
            }
        };

        window.SinggharaMedia.loadProps = async function(track) {
            if (!track || !track.phonePath) return;
            try {
                var info = await window.SinggharaMedia.getJson('/api/storage/prop?root=' +
                        encodeURIComponent(track.root || 'music') + '&path=' + encodeURIComponent(track.phonePath));
                if (info && info.title) track.title = info.title;
                if (info && info.artist) track.artist = info.artist;
                if (info && info.hasArtwork) track.cover = window.SinggharaMedia.withToken('/api/storage/art?root=' +
                        encodeURIComponent(track.root || 'music') + '&path=' + encodeURIComponent(track.phonePath));
                window.updateGlobalPlayerInfo();
            } catch (e) {
            }
        };

        /** Membuat objek track musik dari berkas HP agar kompatibel dengan pemutar yang sudah ada. */
        window.SinggharaMedia.toTrack = function(file, root) {
            return {
                id: 'phone_' + root + '_' + file.path,
                title: file.name.replace(/\.[^.]+${dol}/, ''),
                artist: 'Berkas HP - ' + root,
                folder: 'HP',
                size: file.formattedSize || '',
                url: window.SinggharaMedia.streamUrl({ root: root, path: file.path }, false),
                phonePath: file.path,
                root: root,
                mime: file.mime,
                durationMs: 0,
                addedAt: file.mtime || Date.now()
            };
        };

        // --- TOAST GLOBAL (dipakai halaman musik & media; sebelumnya dipanggil tanpa definisi) ---
        window.showCustomToast = window.showCustomToast || function(message, iconClass) {
            var container = document.getElementById('toastContainer');
            if (!container) return;
            var toast = document.createElement('div');
            toast.className = 'alert alert-dark d-flex align-items-center gap-2 shadow-lg';
            toast.style.cssText = 'border-radius:14px;font-size:13px;max-width:340px;margin:0 0 8px 0;padding:10px 14px;background:var(--bs-body-bg,#222);color:var(--bs-body-color,#fff);border:1px solid var(--border-color,#444);';
            var icon = iconClass || 'bi-info-circle-fill';
            toast.innerHTML = '<i class="bi ' + icon + ' text-primary fs-5"></i><span class="flex-grow-1"></span>';
            toast.lastElementChild.textContent = String(message == null ? '' : message);
            container.appendChild(toast);
            setTimeout(function() {
                toast.style.transition = 'opacity .3s ease';
                toast.style.opacity = '0';
                setTimeout(function() { if (toast.parentNode) toast.parentNode.removeChild(toast); }, 320);
            }, 2600);
        };

        // --- BROWSER BERKAS HP (MODAL) ---
        var mediaBrowserState = { root: 'music', dir: '', kind: 'all', files: [], dirs: [], label: '', mode: 'audio', onPick: null, loading: false, query: '' };

        function openPhoneMediaBrowser(mode, onPick) {
            mediaBrowserState.mode = mode || 'audio';
            mediaBrowserState.kind = mode === 'video' ? 'video' : (mode === 'audio' ? 'audio' : 'all');
            mediaBrowserState.root = mode === 'video' ? 'movies' : 'music';
            mediaBrowserState.dir = '';
            mediaBrowserState.onPick = onPick;
            mediaBrowserState.query = '';
            ensureMediaBrowserMarkup();
            var modal = document.getElementById('phoneMediaBrowserModal');
            if (window.bootstrap && window.bootstrap.Modal) {
                window.bootstrap.Modal.getOrCreateInstance(modal).show();
            } else {
                modal.classList.remove('d-none');
                modal.classList.add('show');
                modal.style.display = 'block';
            }
            loadPhoneMediaRoots();
        }

        function closePhoneMediaBrowser() {
            var modal = document.getElementById('phoneMediaBrowserModal');
            if (!modal) return;
            if (window.bootstrap && window.bootstrap.Modal) {
                var instance = window.bootstrap.Modal.getInstance(modal);
                if (instance) instance.hide();
            } else {
                modal.classList.add('d-none');
                modal.classList.remove('show');
                modal.style.display = '';
            }
        }

        async function loadPhoneMediaRoots() {
            var body = document.getElementById('phoneMediaBrowserBody');
            if (!body) return;
            mediaBrowserState.loading = true;
            body.innerHTML = '<div class="text-center py-5"><span class="spinner-border text-primary"></span><div class="small text-secondary mt-2">Memindai penyimpanan HP...</div></div>';
            try {
                var roots = await window.SinggharaMedia.loadRoots();
                var html = '<div class="row g-2">';
                roots.forEach(function(r) {
                    var allowed = mediaBrowserState.mode === 'all' ||
                            (mediaBrowserState.mode === 'audio' && (r.defaultKind === 'audio' || r.key === 'download' || r.key === 'internal' || r.key === 'documents')) ||
                            (mediaBrowserState.mode === 'video' && (r.defaultKind === 'video' || r.key === 'camera' || r.key === 'download' || r.key === 'internal'));
                    if (!allowed) return;
                    html += '<div class="col-6 col-md-4">' +
                            '<button type="button" class="btn btn-outline-primary w-100 py-3 d-flex flex-column align-items-center gap-1" style="border-radius:16px;" onclick="enterPhoneMediaRoot(' + '"' + r.key + '"' + ')">' +
                            '<i class="bi ' + r.icon + ' fs-4"></i><span class="small fw-bold">' + escapeHtml(r.label) + '</span></button></div>';
                });
                html += '</div>';
                body.innerHTML = html;
                document.getElementById('phoneMediaBrowserPath').textContent = 'Pilih folder di HP';
            } catch (e) {
                body.innerHTML = '<div class="alert alert-warning text-center">Pustaka HP tidak dapat dibaca: ' + escapeHtml(e.message) + '<br><small class="text-secondary">Pastikan sesi PC sudah diverifikasi PIN dan izin akses penyimpanan HP aktif.</small></div>';
            }
            mediaBrowserState.loading = false;
        }

        function enterPhoneMediaRoot(rootKey) {
            mediaBrowserState.root = rootKey;
            mediaBrowserState.dir = '';
            refreshPhoneMediaBrowser();
        }

        function enterPhoneMediaDir(dirPath) {
            mediaBrowserState.dir = dirPath || '';
            refreshPhoneMediaBrowser();
        }

        function goPhoneMediaUp() {
            mediaBrowserState.dir = mediaBrowserState.parent || '';
            refreshPhoneMediaBrowser();
        }

        async function refreshPhoneMediaBrowser() {
            var body = document.getElementById('phoneMediaBrowserBody');
            if (!body) return;
            body.innerHTML = '<div class="text-center py-5"><span class="spinner-border text-primary"></span></div>';
            try {
                var data = await window.SinggharaMedia.list(mediaBrowserState.root, mediaBrowserState.dir, mediaBrowserState.kind, mediaBrowserState.query);
                mediaBrowserState.parent = data.parent || '';
                mediaBrowserState.files = data.files || [];
                var html = '<div class="d-flex align-items-center gap-2 mb-3">' +
                        '<button type="button" class="btn btn-sm btn-outline-secondary rounded-pill" onclick="' +
                        ((data.dir && data.dir.length) ? 'goPhoneMediaUp()' : 'loadPhoneMediaRoots()') + '"><i class="bi bi-arrow-left me-1"></i>' +
                        ((data.dir && data.dir.length) ? 'Naik' : 'Folder Lain') + '</button>' +
                        '<input type="text" class="form-control form-control-sm rounded-pill" placeholder="Cari berkas..." value="' + escapeHtml(mediaBrowserState.query) + '" oninput="phoneMediaSearchChanged(this.value)">' +
                        '</div>';
                html += '<div class="small text-secondary mb-2 text-truncate"><i class="bi bi-folder2-open me-1"></i>' + escapeHtml(data.rootLabel + (data.dir ? ' / ' + data.dir : '')) + '</div>';
                if ((data.dirs || []).length > 0) {
                    html += '<div class="list-group mb-2" style="border-radius:14px; overflow:hidden;">';
                    data.dirs.forEach(function(d) {
                        html += '<button type="button" class="list-group-item list-group-item-action d-flex align-items-center gap-2" onclick="enterPhoneMediaDir(' + '"' + d.path.replace(/"/g, '') + '"' + ')">' +
                                '<i class="bi bi-folder-fill text-warning fs-5"></i><span class="text-truncate">' + escapeHtml(d.name) + '</span></button>';
                    });
                    html += '</div>';
                }
                html += '<div class="list-group" style="border-radius:14px; overflow:hidden;">';
                if (mediaBrowserState.files.length === 0) {
                    html += '<div class="list-group-item text-center text-secondary py-4">Tidak ada berkas di folder ini.</div>';
                }
                mediaBrowserState.files.forEach(function(f, idx) {
                    var icon = f.kind === 'audio' ? 'bi-music-note-beamed text-primary' : (f.kind === 'video' ? 'bi-camera-reels-fill text-danger' : 'bi-file-earmark text-secondary');
                    html += '<div class="list-group-item list-group-item-action d-flex align-items-center gap-2" onclick="pickPhoneMediaFile(' + idx + ')" style="cursor:pointer;">' +
                            '<i class="bi ' + icon + ' fs-5"></i>' +
                            '<div class="flex-grow-1 overflow-hidden">' +
                            '<div class="text-truncate fw-semibold" style="font-size:13px;">' + escapeHtml(f.name) + '</div>' +
                            '<div class="small text-secondary">' + escapeHtml(f.formattedSize || '') + (f.mime ? ' - ' + escapeHtml(f.mime) : '') + '</div>' +
                            '</div>' +
                            '<button type="button" class="btn btn-sm btn-outline-success rounded-circle" onclick="event.stopPropagation(); downloadPhoneMediaFile(' + idx + ')" title="Unduh ke PC"><i class="bi bi-download"></i></button>' +
                            '</div>';
                });
                html += '</div>';
                body.innerHTML = html;
                document.getElementById('phoneMediaBrowserPath').textContent = (data.rootLabel || '') + (data.dir ? ' / ' + data.dir : '');
            } catch (e) {
                body.innerHTML = '<div class="alert alert-danger text-center">Gagal memuat folder: ' + escapeHtml(e.message) + '</div>';
            }
        }

        function phoneMediaSearchChanged(value) {
            mediaBrowserState.query = value || '';
            if (window.phoneMediaSearchTimer) clearTimeout(window.phoneMediaSearchTimer);
            window.phoneMediaSearchTimer = setTimeout(function() { refreshPhoneMediaBrowser(); }, 450);
        }

        function pickPhoneMediaFile(index) {
            var file = mediaBrowserState.files[index];
            if (!file) return;
            var track = window.SinggharaMedia.toTrack(file, mediaBrowserState.root);
            if (mediaBrowserState.onPick) {
                mediaBrowserState.onPick(track, mediaBrowserState.files, mediaBrowserState.root);
            } else {
                addPhoneTrackToQueue(track, true);
            }
            closePhoneMediaBrowser();
        }

        function downloadPhoneMediaFile(index) {
            var file = mediaBrowserState.files[index];
            if (!file) return;
            var link = document.createElement('a');
            link.href = window.SinggharaMedia.streamUrl({ root: mediaBrowserState.root, path: file.path }, true);
            link.download = file.name;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        }

        /** Menambahkan track HP ke daftar putar aktif lalu memutarnya. */
        function addPhoneTrackToQueue(track, autoplay) {
            var exists = window.musicPlaylist.findIndex(function(t) { return t.id === track.id; });
            var index = exists >= 0 ? exists : window.musicPlaylist.length;
            if (exists < 0) {
                window.musicPlaylist.push(track);
                if (window.renderActiveMusicTabContent) window.renderActiveMusicTabContent();
            }
            if (window.updateMiniPlayerInfo) window.updateMiniPlayerInfo();
            if (autoplay) {
                if (window.playTrackByIndex) window.playTrackByIndex(index);
                window.loadPhoneTrackExtras(track);
            }
            if (window.showCustomToast) window.showCustomToast('Memutar berkas HP: ' + track.title);
            return index;
        }

        window.loadPhoneTrackExtras = function(track) {
            if (!track || !track.phonePath) return;
            window.SinggharaMedia.loadProps(track);
            window.SinggharaMedia.loadLyrics(track);
        };

        function ensureMediaBrowserMarkup() {
            if (document.getElementById('phoneMediaBrowserModal')) return;
            var wrapper = document.createElement('div');
            wrapper.innerHTML = '' +
                '<div class="modal fade" id="phoneMediaBrowserModal" tabindex="-1" aria-hidden="true">' +
                '  <div class="modal-dialog modal-lg modal-dialog-centered modal-dialog-scrollable">' +
                '    <div class="modal-content card-custom" style="border-radius:20px;">' +
                '      <div class="modal-header border-0 pb-0">' +
                '        <div><h5 class="modal-title fw-bold"><i class="bi bi-phone me-2 text-primary"></i>Berkas di HP</h5>' +
                '        <small class="text-secondary" id="phoneMediaBrowserPath">Pilih folder di HP</small></div>' +
                '        <button type="button" class="btn-close" onclick="closePhoneMediaBrowser()"></button>' +
                '      </div>' +
                '      <div class="modal-body" id="phoneMediaBrowserBody"></div>' +
                '    </div>' +
                '  </div>' +
                '</div>';
            document.body.appendChild(wrapper.firstElementChild);
        }

        // --- NOW PLAYING GLOBAL (mini player di semua halaman) ---
        var globalPlayerVisible = false;

        function globalPlayerCss() {
            var style = document.createElement('style');
            style.id = 'singgharaGlobalPlayerCss';
            style.textContent = '' +
                '#singgharaGlobalPlayer{position:fixed;left:0;right:0;bottom:0;z-index:1075;display:none;' +
                '  background:var(--bs-body-bg,#1c1c1e);border-top:1px solid var(--border-color,#333);' +
                '  box-shadow:0 -8px 24px rgba(0,0,0,.28);padding:6px 12px;}' +
                '#singgharaGlobalPlayer.visible{display:block;}' +
                'body.global-player-shown .content-wrapper{padding-bottom:96px !important;}' +
                '#singgharaGlobalPlayer .gp-art{width:46px;height:46px;border-radius:12px;object-fit:cover;background:var(--bg-hover,#2a2a2e);' +
                '  display:flex;align-items:center;justify-content:center;overflow:hidden;flex:0 0 46px;}' +
                '#singgharaGlobalPlayer .gp-art img{width:100%;height:100%;object-fit:cover;}' +
                '#singgharaGlobalPlayer .gp-seek{height:4px;border-radius:99px;background:var(--bg-hover,#3a3a3e);cursor:pointer;position:relative;}' +
                '#singgharaGlobalPlayer .gp-seek-fill{height:100%;border-radius:99px;background:linear-gradient(90deg,#E91E63,#8338EC);width:0%;}' +
                '#singgharaGlobalPlayer .gp-lyric{font-size:11px;color:var(--bs-primary,#E91E63);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%;}' +
                '#singgharaGlobalPlayer button{border:0;background:transparent;color:var(--bs-body-color,#fff);}' +
                '#singgharaGlobalPlayerExpand{position:fixed;inset:0;z-index:1080;display:none;overflow-y:auto;background:var(--bs-body-bg,#111);}' +
                '#singgharaGlobalPlayerExpand.visible{display:block;}' +
                '.gp-line{padding:8px 12px;border-radius:12px;cursor:pointer;font-size:15px;color:var(--bs-secondary-color,#9aa);transition:all .18s ease;}' +
                '.gp-line:hover{background:var(--bg-hover,#222);}' +
                '.gp-line.active-lrc-line{color:var(--bs-primary,#E91E63);font-weight:700;transform:scale(1.02);background:rgba(233,30,99,.12);}';
            document.head.appendChild(style);
        }

        function buildGlobalPlayerMarkup() {
            if (document.getElementById('singgharaGlobalPlayer')) return;
            globalPlayerCss();
            var host = document.querySelector('.content-wrapper') || document.body;
            var bar = document.createElement('div');
            bar.id = 'singgharaGlobalPlayer';
            bar.innerHTML = '' +
                '<div class="d-flex align-items-center gap-2 flex-nowrap">' +
                '  <div class="gp-art" id="gpArt" onclick="expandGlobalPlayer()"><i class="bi bi-music-note-beamed text-primary fs-5"></i></div>' +
                '  <div style="min-width:0;flex:1 1 140px;" onclick="expandGlobalPlayer()">' +
                '    <div class="fw-bold text-truncate" id="gpTitle" style="font-size:13px;">Tidak ada yang diputar</div>' +
                '    <div class="gp-lyric" id="gpLyric">Lirik akan tampil di sini</div>' +
                '  </div>' +
                '  <div class="d-none d-md-flex align-items-center gap-1">' +
                '    <button type="button" onclick="globalPlayerPrev()" title="Sebelumnya"><i class="bi bi-skip-backward-fill fs-5"></i></button>' +
                '    <button type="button" id="gpPlayBtn" onclick="globalPlayerToggle()" title="Putar / Jeda"><i class="bi bi-play-fill fs-4"></i></button>' +
                '    <button type="button" onclick="globalPlayerNext()" title="Berikutnya"><i class="bi bi-skip-forward-fill fs-5"></i></button>' +
                '  </div>' +
                '  <div class="d-md-none d-flex align-items-center gap-1">' +
                '    <button type="button" id="gpPlayBtnMobile" onclick="globalPlayerToggle()" title="Putar / Jeda"><i class="bi bi-play-fill fs-4"></i></button>' +
                '  </div>' +
                '  <div class="d-none d-lg-block flex-shrink-0" style="width:180px;">' +
                '    <div class="gp-seek" onclick="globalPlayerSeekFromEvent(event)"><div class="gp-seek-fill" id="gpSeekFill"></div></div>' +
                '    <div class="d-flex justify-content-between" style="font-size:10px;color:var(--bs-secondary-color,#999);"><span id="gpCurrent">0:00</span><span id="gpTotal">0:00</span></div>' +
                '  </div>' +
                '  <div class="d-flex align-items-center gap-1 flex-shrink-0">' +
                '    <button type="button" class="d-none d-sm-block" onclick="openPhoneMediaBrowser(\'audio\', null)" title="Putar berkas musik dari HP"><i class="bi bi-phone fs-5"></i></button>' +
                '    <button type="button" class="d-none d-sm-block" onclick="globalPlayerToggleLyrics()" title="Lirik"><i class="bi bi-chat-quote fs-5"></i></button>' +
                '    <button type="button" onclick="hideGlobalPlayer()" title="Sembunyikan"><i class="bi bi-x-lg"></i></button>' +
                '  </div>' +
                '</div>';
            host.appendChild(bar);

            var panel = document.createElement('div');
            panel.id = 'singgharaGlobalPlayerExpand';
            panel.innerHTML = '' +
                '<div class="container py-3" style="max-width:900px;">' +
                '  <div class="d-flex justify-content-between align-items-center mb-3">' +
                '    <h5 class="fw-bold mb-0"><i class="bi bi-music-note-list me-2 text-primary"></i>Sedang Diputar</h5>' +
                '    <button type="button" class="btn btn-sm btn-outline-secondary rounded-pill" onclick="collapseGlobalPlayer()"><i class="bi bi-chevron-down me-1"></i>Tutup</button>' +
                '  </div>' +
                '  <div class="row g-3">' +
                '    <div class="col-12 col-md-5">' +
                '      <div class="rounded-4 overflow-hidden d-flex align-items-center justify-content-center" style="aspect-ratio:1/1;background:var(--bg-hover,#222);" id="gpBigArt">' +
                '        <i class="bi bi-music-note-beaded" style="font-size:64px;color:var(--bs-primary,#E91E63);"></i></div>' +
                '      <div class="mt-3"><div class="fw-bold fs-5 text-truncate" id="gpBigTitle">-</div>' +
                '      <div class="text-secondary small text-truncate" id="gpBigArtist">-</div></div>' +
                '      <div class="mt-3"><div class="gp-seek" onclick="globalPlayerSeekFromEvent(event)"><div class="gp-seek-fill" id="gpSeekFillBig"></div></div>' +
                '      <div class="d-flex justify-content-between" style="font-size:12px;color:var(--bs-secondary-color,#999);"><span id="gpCurrentBig">0:00</span><span id="gpTotalBig">0:00</span></div></div>' +
                '      <div class="d-flex align-items-center justify-content-center gap-3 mt-2">' +
                '        <button type="button" onclick="globalPlayerToggleShuffle()" id="gpShuffleBtn" title="Acak"><i class="bi bi-shuffle"></i></button>' +
                '        <button type="button" onclick="globalPlayerPrev()" title="Sebelumnya"><i class="bi bi-skip-backward-fill fs-3"></i></button>' +
                '        <button type="button" id="gpPlayBtnBig" onclick="globalPlayerToggle()" class="rounded-circle d-flex align-items-center justify-content-center" style="width:56px;height:56px;background:var(--bs-primary,#E91E63);color:#fff;"><i class="bi bi-play-fill fs-3"></i></button>' +
                '        <button type="button" onclick="globalPlayerNext()" title="Berikutnya"><i class="bi bi-skip-forward-fill fs-3"></i></button>' +
                '        <button type="button" onclick="globalPlayerCycleRepeat()" id="gpRepeatBtn" title="Ulang"><i class="bi bi-repeat"></i></button>' +
                '      </div>' +
                '      <div class="d-flex align-items-center gap-2 mt-3">' +
                '        <i class="bi bi-volume-up text-secondary"></i>' +
                '        <input type="range" class="form-range" min="0" max="1" step="0.01" value="1" onchange="globalPlayerSetVolume(this.value)" oninput="globalPlayerSetVolume(this.value)">' +
                '        <select class="form-select form-select-sm" style="width:92px;" onchange="globalPlayerSetSpeed(this.value)">' +
                '          <option value="0.75">0.75x</option><option value="1" selected>1x</option><option value="1.25">1.25x</option><option value="1.5">1.5x</option><option value="2">2x</option>' +
                '        </select>' +
                '      </div>' +
                '      <div class="mt-3 d-flex gap-2 flex-wrap">' +
                '        <button type="button" class="btn btn-sm btn-outline-primary rounded-pill" onclick="openPhoneMediaBrowser(\'audio\', null)"><i class="bi bi-phone me-1"></i>Berkas HP</button>' +
                '        <button type="button" class="btn btn-sm btn-outline-success rounded-pill" onclick="switchTabByName(\'musik\')"><i class="bi bi-music-player me-1"></i>Halaman Musik</button>' +
                '        <button type="button" class="btn btn-sm btn-outline-danger rounded-pill" onclick="globalPlayerToggleFavorite()" id="gpFavBtn"><i class="bi bi-heart"></i></button>' +
                '      </div>' +
                '    </div>' +
                '    <div class="col-12 col-md-7">' +
                '      <div class="nav nav-pills mb-2" id="gpTabs">' +
                '        <button type="button" class="nav-link active rounded-pill" onclick="switchGlobalPlayerPanel(\'lyrics\', this)"><i class="bi bi-chat-quote me-1"></i>Lirik</button>' +
                '        <button type="button" class="nav-link rounded-pill" onclick="switchGlobalPlayerPanel(\'queue\', this)"><i class="bi bi-list-ol me-1"></i>Antrean</button>' +
                '      </div>' +
                '      <div id="gpLyricsPane" style="max-height:60vh;overflow-y:auto;"></div>' +
                '      <div id="gpQueuePane" style="max-height:60vh;overflow-y:auto;display:none;"></div>' +
                '    </div>' +
                '  </div>' +
                '</div>';
            document.body.appendChild(panel);
        }

        function currentGlobalTrack() {
            return (window.musicPlaylist && window.musicPlaylist[window.currentMusicIndex]) ? window.musicPlaylist[window.currentMusicIndex] : null;
        }

        window.updateGlobalPlayerInfo = function() {
            var bar = document.getElementById('singgharaGlobalPlayer');
            if (!bar) return;
            var track = currentGlobalTrack();
            if (!track) {
                document.getElementById('gpTitle').textContent = 'Tidak ada yang diputar';
                return;
            }
            document.getElementById('gpTitle').textContent = track.title || 'Tanpa Judul';
            var bigTitle = document.getElementById('gpBigTitle');
            if (bigTitle) bigTitle.textContent = track.title || 'Tanpa Judul';
            var bigArtist = document.getElementById('gpBigArtist');
            if (bigArtist) bigArtist.textContent = track.artist || '-';

            var artHost = document.getElementById('gpArt');
            var cover = track.cover || window.musicCustomAlbumArts[track.id];
            if (artHost) {
                artHost.innerHTML = cover ? '<img src="' + cover + '" alt="">' : '<i class="bi bi-music-note-beamed text-primary fs-5"></i>';
            }
            var bigArt = document.getElementById('gpBigArt');
            if (bigArt) {
                bigArt.innerHTML = cover ? '<img src="' + cover + '" alt="" style="width:100%;height:100%;object-fit:cover;">' : '<i class="bi bi-music-note-beaded" style="font-size:64px;color:var(--bs-primary,#E91E63);"></i>';
            }
            var favBtn = document.getElementById('gpFavBtn');
            if (favBtn) {
                var isFav = window.isTrackFavorite ? window.isTrackFavorite(track) : false;
                favBtn.innerHTML = isFav ? '<i class="bi bi-heart-fill"></i>' : '<i class="bi bi-heart"></i>';
            }
            window.refreshGlobalLyricsView();
            window.renderGlobalQueue();
        };

        window.updateGlobalPlayerProgress = function() {
            var audio = window.musicPlayerAudio;
            if (!audio) return;
            var current = audio.currentTime || 0;
            var total = audio.duration || 0;
            var percent = total > 0 ? (current / total) * 100 : 0;
            ['gpSeekFill', 'gpSeekFillBig'].forEach(function(id) {
                var el = document.getElementById(id);
                if (el) el.style.width = percent.toFixed(2) + '%';
            });
            var curText = window.formatAudioTime ? window.formatAudioTime(current) : Math.floor(current) + 's';
            var totText = window.formatAudioTime ? window.formatAudioTime(total) : Math.floor(total) + 's';
            ['gpCurrent', 'gpCurrentBig'].forEach(function(id) {
                var el = document.getElementById(id);
                if (el) el.textContent = curText;
            });
            ['gpTotal', 'gpTotalBig'].forEach(function(id) {
                var el = document.getElementById(id);
                if (el) el.textContent = totText;
            });
            window.refreshGlobalLyricLine(current);
        };

        window.refreshGlobalLyricLine = function(currentSec) {
            var el = document.getElementById('gpLyric');
            if (!el) return;
            var track = currentGlobalTrack();
            if (!track) {
                el.textContent = 'Lirik akan tampil di sini';
                return;
            }
            var raw = window.musicManualLyrics[track.id] || window.musicManualLyrics[track.title] || '';
            if (!raw) {
                el.textContent = track.phonePath ? 'Mengambil lirik dari HP...' : 'Tidak ada lirik untuk trek ini';
                return;
            }
            if (!window.cachedGlobalLyrics || window.cachedGlobalLyricsFor !== track.id) {
                window.cachedGlobalLyrics = window.parseLrcLyrics ? window.parseLrcLyrics(raw) : [];
                window.cachedGlobalLyricsFor = track.id;
            }
            var lines = window.cachedGlobalLyrics || [];
            var activeIdx = -1;
            for (var i = 0; i < lines.length; i++) {
                if (lines[i].timeSec <= currentSec) activeIdx = i; else break;
            }
            if (activeIdx < 0 && lines.length) activeIdx = 0;
            el.textContent = activeIdx >= 0 && lines[activeIdx] ? lines[activeIdx].text : 'Lirik akan tampil di sini';
            var activeNodes = document.querySelectorAll('#gpLyricsPane .gp-line');
            for (var n = 0; n < activeNodes.length; n++) {
                if (n === activeIdx) {
                    if (!activeNodes[n].classList.contains('active-lrc-line')) {
                        activeNodes[n].classList.add('active-lrc-line');
                        if (activeNodes[n].scrollIntoView) activeNodes[n].scrollIntoView({ behavior: 'smooth', block: 'center' });
                    }
                } else {
                    activeNodes[n].classList.remove('active-lrc-line');
                }
            }
        };

        window.refreshGlobalLyricsView = function() {
            var pane = document.getElementById('gpLyricsPane');
            if (!pane) return;
            var track = currentGlobalTrack();
            if (!track) {
                pane.innerHTML = '<div class="text-secondary text-center py-4">Belum ada trek yang dipilih.</div>';
                return;
            }
            var raw = window.musicManualLyrics[track.id] || window.musicManualLyrics[track.title] || '';
            if (!raw) {
                pane.innerHTML = '<div class="text-secondary text-center py-4">' +
                        (track.phonePath ? 'Lirik tidak ditemukan di HP (tempelkan berkas .lrc di samping lagu).' : 'Tidak ada lirik tersimpan untuk trek ini.') +
                        '<div class="mt-3"><button type="button" class="btn btn-sm btn-outline-primary rounded-pill" onclick="window.refreshGlobalLyricsRetry()"><i class="bi bi-arrow-clockwise me-1"></i>Coba lagi</button></div></div>';
                return;
            }
            var lines = window.parseLrcLyrics ? window.parseLrcLyrics(raw) : [];
            if (!lines.length) {
                pane.innerHTML = '<div class="gp-line">' + escapeHtml(raw) + '</div>';
                return;
            }
            var html = '';
            lines.forEach(function(l, idx) {
                html += '<div class="gp-line" onclick="globalPlayerSeekTo(' + l.timeSec.toFixed(3) + ')">' + escapeHtml(l.text) + '</div>';
            });
            pane.innerHTML = html;
            window.refreshGlobalLyricLine(window.musicPlayerAudio ? (window.musicPlayerAudio.currentTime || 0) : 0);
        };

        window.refreshGlobalLyricsRetry = function() {
            var track = currentGlobalTrack();
            if (track && track.phonePath) {
                window.SinggharaMedia.loadLyrics(track).then(function() { window.refreshGlobalLyricsView(); });
            } else {
                window.refreshGlobalLyricsView();
            }
        };

        window.renderGlobalQueue = function() {
            var pane = document.getElementById('gpQueuePane');
            if (!pane) return;
            var list = window.musicPlaylist || [];
            if (!list.length) {
                pane.innerHTML = '<div class="text-secondary text-center py-4">Daftar putar kosong. Tambahkan dari berkas HP.</div>';
                return;
            }
            var html = '<div class="list-group" style="border-radius:14px;overflow:hidden;">';
            list.forEach(function(t, idx) {
                var active = idx === window.currentMusicIndex;
                html += '<button type="button" class="list-group-item list-group-item-action d-flex align-items-center gap-2 ' + (active ? 'active' : '') + '" onclick="globalPlayerPlayIndex(' + idx + ')">' +
                        '<span class="small" style="width:22px;">' + (idx + 1) + '</span>' +
                        '<span class="flex-grow-1 text-truncate" style="font-size:13px;">' + escapeHtml(t.title || 'Tanpa Judul') + '</span>' +
                        '<span class="small opacity-75">' + escapeHtml(t.artist || '') + '</span></button>';
            });
            html += '</div>';
            pane.innerHTML = html;
        };

        function switchGlobalPlayerPanel(which, btn) {
            document.querySelectorAll('#gpTabs .nav-link').forEach(function(el) { el.classList.remove('active'); });
            if (btn) btn.classList.add('active');
            var lyrics = document.getElementById('gpLyricsPane');
            var queue = document.getElementById('gpQueuePane');
            if (lyrics) lyrics.style.display = which === 'lyrics' ? 'block' : 'none';
            if (queue) queue.style.display = which === 'queue' ? 'block' : 'none';
        }

        window.expandGlobalPlayer = function() {
            var panel = document.getElementById('singgharaGlobalPlayerExpand');
            if (!panel) return;
            panel.classList.add('visible');
            window.updateGlobalPlayerInfo();
        };

        window.collapseGlobalPlayer = function() {
            var panel = document.getElementById('singgharaGlobalPlayerExpand');
            if (panel) panel.classList.remove('visible');
        };

        window.hideGlobalPlayer = function() {
            var bar = document.getElementById('singgharaGlobalPlayer');
            if (bar) bar.classList.remove('visible');
            document.body.classList.remove('global-player-shown');
            globalPlayerVisible = false;
            window.globalPlayerHiddenByUser = true;
        };

        window.showGlobalPlayer = function() {
            var bar = document.getElementById('singgharaGlobalPlayer');
            if (!bar) return;
            if (window.currentTab === 'musik' || window.globalPlayerHiddenByUser) {
                bar.classList.remove('visible');
                document.body.classList.remove('global-player-shown');
                return;
            }
            bar.classList.add('visible');
            document.body.classList.add('global-player-shown');
            globalPlayerVisible = true;
            window.updateGlobalPlayerInfo();
        };

        window.globalPlayerToggle = function() {
            if (window.togglePlayMusic) window.togglePlayMusic();
        };
        window.globalPlayerPrev = function() {
            if (window.prevMusicTrack) window.prevMusicTrack();
        };
        window.globalPlayerNext = function() {
            if (window.nextMusicTrack) window.nextMusicTrack(true);
        };
        window.globalPlayerPlayIndex = function(idx) {
            if (window.playTrackByIndex) window.playTrackByIndex(idx);
        };
        window.globalPlayerSeekTo = function(seconds) {
            if (!window.musicPlayerAudio) return;
            try {
                window.musicPlayerAudio.currentTime = seconds;
                if (window.handleMusicSeekChange) window.handleMusicSeekChange(seconds);
            } catch (e) {
            }
        };
        window.globalPlayerSeekFromEvent = function(event) {
            var audio = window.musicPlayerAudio;
            if (!audio || !audio.duration) return;
            var rect = event.currentTarget.getBoundingClientRect();
            var ratio = (event.clientX - rect.left) / rect.width;
            window.globalPlayerSeekTo(Math.max(0, Math.min(1, ratio)) * audio.duration);
        };
        window.globalPlayerSetVolume = function(value) {
            if (!window.musicPlayerAudio) return;
            var volume = parseFloat(value);
            window.musicPlayerAudio.volume = isNaN(volume) ? 1 : volume;
            if (window.saveMusicSettings) window.saveMusicSettings();
        };
        window.globalPlayerSetSpeed = function(value) {
            var audio = window.musicPlayerAudio;
            if (!audio) return;
            var rate = parseFloat(value) || 1;
            try {
                audio.playbackRate = rate;
                audio.defaultPlaybackRate = rate;
            } catch (e) {
            }
        };
        window.globalPlayerToggleShuffle = function() {
            if (window.toggleShuffleMode) window.toggleShuffleMode();
            var btn = document.getElementById('gpShuffleBtn');
            if (btn) btn.style.color = window.isMusicShuffle ? 'var(--bs-primary,#E91E63)' : '';
        };
        window.globalPlayerCycleRepeat = function() {
            if (window.cycleRepeatMode) window.cycleRepeatMode();
            var btn = document.getElementById('gpRepeatBtn');
            if (btn) {
                var mode = window.musicRepeatMode || 'NONE';
                btn.innerHTML = mode === 'ONE' ? '<i class="bi bi-repeat-1"></i>' : '<i class="bi bi-repeat"></i>';
                btn.style.color = mode === 'NONE' ? '' : 'var(--bs-primary,#E91E63)';
            }
        };
        window.globalPlayerToggleLyrics = function() {
            window.expandGlobalPlayer();
            switchGlobalPlayerPanel('lyrics', document.querySelector('#gpTabs .nav-link'));
        };
        window.globalPlayerToggleFavorite = function() {
            var track = currentGlobalTrack();
            if (!track || !window.toggleTrackFavorite) return;
            window.toggleTrackFavorite(track.id, null);
            window.updateGlobalPlayerInfo();
        };

        function bindGlobalPlayerAudio() {
            var audio = window.musicPlayerAudio;
            if (!audio || audio.dataset.globalPlayerBound === '1') return;
            audio.dataset.globalPlayerBound = '1';
            audio.addEventListener('play', function() {
                window.globalPlayerHiddenByUser = false;
                window.showGlobalPlayer();
                window.updateGlobalPlayerInfo();
                ['gpPlayBtn', 'gpPlayBtnMobile', 'gpPlayBtnBig'].forEach(function(id) {
                    var el = document.getElementById(id);
                    if (el) el.innerHTML = el.id === 'gpPlayBtnBig' ? '<i class="bi bi-pause-fill fs-3"></i>' : '<i class="bi bi-pause-fill fs-4"></i>';
                });
            });
            audio.addEventListener('pause', function() {
                ['gpPlayBtn', 'gpPlayBtnMobile', 'gpPlayBtnBig'].forEach(function(id) {
                    var el = document.getElementById(id);
                    if (el) el.innerHTML = el.id === 'gpPlayBtnBig' ? '<i class="bi bi-play-fill fs-3"></i>' : '<i class="bi bi-play-fill fs-4"></i>';
                });
            });
            audio.addEventListener('loadedmetadata', function() {
                window.updateGlobalPlayerProgress();
            });
            audio.addEventListener('error', function() {
                var el = document.getElementById('gpLyric');
                if (el) el.textContent = 'Berkas tidak dapat diputar browser';
            });
        }

        // --- HALAMAN PEMUTAR VIDEO ---
        var videoState = { root: 'movies', dir: '', files: [], index: 0, title: '', query: '' };

        function loadVideo() {
            var host = document.getElementById('tab-content');
            if (!host) return;
            var html = '' +
                '<div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">' +
                '  <div><h4 class="fw-bold mb-0 text-danger"><i class="bi bi-camera-reels-fill me-2"></i>Pemutar Video</h4>' +
                '  <small class="text-secondary">Memutar video langsung dari penyimpanan HP (100% offline, mendukung seek).</small></div>' +
                '  <div class="d-flex gap-2 flex-wrap">' +
                '    <button type="button" class="btn btn-sm btn-danger rounded-pill" onclick="openPhoneMediaBrowser(\'video\', startPhoneVideo)"><i class="bi bi-phone me-1"></i>Pilih Video di HP</button>' +
                '    <button type="button" class="btn btn-sm btn-outline-secondary rounded-pill" onclick="loadVideoLibrary(\'movies\', \'\')"><i class="bi bi-folder2-open me-1"></i>Folder Movies</button>' +
                '    <button type="button" class="btn btn-sm btn-outline-secondary rounded-pill" onclick="loadVideoLibrary(\'camera\', \'\')"><i class="bi bi-camera-fill me-1"></i>DCIM</button>' +
                '  </div>' +
                '</div>' +
                '<div class="row g-3">' +
                '  <div class="col-12 col-lg-8">' +
                '    <div class="card card-custom p-2" style="border-radius:18px;">' +
                '      <video id="phoneVideoElement" class="w-100 rounded-3" controls preload="metadata" style="background:#000;max-height:62vh;" onended="videoTrackEnded()"></video>' +
                '      <div class="d-flex align-items-center justify-content-between px-2 py-1 flex-wrap gap-2">' +
                '        <div class="text-truncate fw-semibold" id="videoNowTitle" style="max-width:60%;">Belum ada video dipilih</div>' +
                '        <div class="d-flex align-items-center gap-1">' +
                '          <button type="button" class="btn btn-sm btn-outline-secondary rounded-circle" onclick="videoPrev()" title="Sebelumnya"><i class="bi bi-skip-backward-fill"></i></button>' +
                '          <button type="button" class="btn btn-sm btn-outline-secondary rounded-circle" onclick="videoNext()" title="Berikutnya"><i class="bi bi-skip-forward-fill"></i></button>' +
                '          <button type="button" class="btn btn-sm btn-outline-secondary rounded-circle" onclick="videoToggleShuffle()" title="Acak"><i class="bi bi-shuffle"></i></button>' +
                '          <button type="button" class="btn btn-sm btn-outline-secondary rounded-circle" onclick="videoSpeed()" id="videoSpeedBtn" title="Kecepatan">1x</button>' +
                '          <button type="button" class="btn btn-sm btn-outline-danger rounded-circle" onclick="videoFullscreen()" title="Layar penuh"><i class="bi bi-arrows-fullscreen"></i></button>' +
                '        </div>' +
                '      </div>' +
                '    </div>' +
                '  </div>' +
                '  <div class="col-12 col-lg-4">' +
                '    <div class="card card-custom p-3" style="border-radius:18px;">' +
                '      <div class="d-flex align-items-center justify-content-between mb-2">' +
                '        <span class="fw-bold"><i class="bi bi-list-ul me-1"></i>Daftar Video</span>' +
                '        <span class="badge rounded-pill text-bg-danger" id="videoCount">0</span>' +
                '      </div>' +
                '      <input type="text" class="form-control form-control-sm rounded-pill mb-2" placeholder="Cari video..." onchange="videoSearchChanged(this.value)">' +
                '      <div class="small text-secondary mb-2 text-truncate" id="videoCurrentPath">Folder: -</div>' +
                '      <div id="videoPlaylistHost" style="max-height:52vh;overflow-y:auto;"></div>' +
                '      <button type="button" class="btn btn-sm btn-outline-primary rounded-pill w-100 mt-2" onclick="loadVideoRoots()"><i class="bi bi-hdd me-1"></i>Pilih folder HP lain</button>' +
                '    </div>' +
                '  </div>' +
                '</div>';
            host.innerHTML = html;
            if (!videoState.files.length) {
                loadVideoLibrary(videoState.root, videoState.dir);
            } else {
                renderVideoPlaylist();
            }
        }

        async function loadVideoRoots() {
            openPhoneMediaBrowser('video', startPhoneVideo);
        }

        async function loadVideoLibrary(root, dir) {
            videoState.root = root;
            videoState.dir = dir || '';
            var host = document.getElementById('videoPlaylistHost');
            if (!host) return;
            host.innerHTML = '<div class="text-center py-4"><span class="spinner-border text-danger"></span></div>';
            try {
                var data = await window.SinggharaMedia.list(root, videoState.dir, 'video', videoState.query);
                videoState.files = data.files || [];
                videoState.parent = data.parent || '';
                videoState.label = data.rootLabel || root;
                var pathLabel = document.getElementById('videoCurrentPath');
                if (pathLabel) {
                    pathLabel.innerHTML = '<i class="bi bi-folder2-open me-1"></i>' + escapeHtml(videoState.label + (videoState.dir ? ' / ' + videoState.dir : '')) +
                            (videoState.dir ? ' <button type="button" class="btn btn-sm btn-link p-0 align-baseline" onclick="loadVideoLibrary(' + '"' + root + '"' + ',' + '"' + (videoState.parent || '') + '"' + ')">Naik</button>' : '');
                }
                var html = '';
                (data.dirs || []).forEach(function(d) {
                    html += '<button type="button" class="btn btn-sm btn-outline-warning w-100 text-start mb-1 rounded-3" onclick="loadVideoLibrary(' + '"' + root + '"' + ',' + '"' + d.path.replace(/"/g, '') + '"' + ')"><i class="bi bi-folder-fill me-1"></i>' + escapeHtml(d.name) + '</button>';
                });
                html += '<div class="list-group" style="border-radius:14px;overflow:hidden;">';
                if (!videoState.files.length) html += '<div class="list-group-item text-center text-secondary py-3">Tidak ada video di folder ini.</div>';
                videoState.files.forEach(function(f, idx) {
                    html += '<button type="button" class="list-group-item list-group-item-action d-flex align-items-center gap-2" onclick="playVideoIndex(' + idx + ')">' +
                            '<i class="bi bi-play-circle-fill text-danger"></i><span class="flex-grow-1 text-truncate" style="font-size:13px;">' + escapeHtml(f.name) + '</span>' +
                            '<span class="small text-secondary">' + escapeHtml(f.formattedSize || '') + '</span></button>';
                });
                html += '</div>';
                host.innerHTML = html;
                renderVideoPlaylist();
            } catch (e) {
                host.innerHTML = '<div class="alert alert-warning py-3 small">Daftar video gagal dimuat: ' + escapeHtml(e.message) + '</div>';
            }
        }

        function videoSearchChanged(value) {
            videoState.query = value || '';
            loadVideoLibrary(videoState.root, videoState.dir);
        }

        function renderVideoPlaylist() {
            var badge = document.getElementById('videoCount');
            if (badge) badge.textContent = String(videoState.files.length);
        }

        function startPhoneVideo(track) {
            var element = document.getElementById('phoneVideoElement');
            if (!element) return;
            if (window.musicPlayerAudio) window.musicPlayerAudio.pause();
            element.src = track.url;
            element.play().catch(function() {
            });
            var title = document.getElementById('videoNowTitle');
            if (title) title.textContent = track.title;
        }

        function playVideoIndex(idx) {
            var file = videoState.files[idx];
            if (!file) return;
            videoState.index = idx;
            startPhoneVideo({
                url: window.SinggharaMedia.streamUrl({ root: videoState.root, path: file.path }, false),
                title: file.name
            });
        }

        function videoNext() {
            if (!videoState.files.length) return;
            var next = videoState.shuffle ? Math.floor(Math.random() * videoState.files.length) : (videoState.index + 1) % videoState.files.length;
            videoState.index = next;
            playVideoIndex(next);
        }

        function videoPrev() {
            if (!videoState.files.length) return;
            var prev = (videoState.index - 1 + videoState.files.length) % videoState.files.length;
            videoState.index = prev;
            playVideoIndex(prev);
        }

        function videoTrackEnded() {
            if (videoState.autoplayNext) videoNext();
        }

        function videoToggleShuffle() {
            videoState.shuffle = !videoState.shuffle;
            videoState.autoplayNext = videoState.shuffle ? videoState.autoplayNext : videoState.autoplayNext;
            if (window.showCustomToast) window.showCustomToast(videoState.shuffle ? 'Video acak aktif' : 'Video acak mati');
        }

        function videoSpeed() {
            var element = document.getElementById('phoneVideoElement');
            if (!element) return;
            var rates = [0.5, 0.75, 1, 1.25, 1.5, 2];
            var current = rates.indexOf(element.playbackRate);
            var next = rates[(current + 1 + rates.length) % rates.length];
            element.playbackRate = next;
            var btn = document.getElementById('videoSpeedBtn');
            if (btn) btn.textContent = next + 'x';
        }

        function videoFullscreen() {
            var element = document.getElementById('phoneVideoElement');
            if (!element) return;
            if (element.requestFullscreen) element.requestFullscreen();
            else if (element.webkitRequestFullscreen) element.webkitRequestFullscreen();
        }

        // --- BOOT ---
        function initSinggharaMediaHub() {
            buildGlobalPlayerMarkup();
            bindGlobalPlayerAudio();
            var originalSwitchTab = window.switchTab;
            window.switchTab = function(tab, element) {
                if (originalSwitchTab) originalSwitchTab(tab, element);
                var bar = document.getElementById('singgharaGlobalPlayer');
                var panel = document.getElementById('singgharaGlobalPlayerExpand');
                if (tab === 'musik') {
                    if (bar) { bar.classList.remove('visible'); document.body.classList.remove('global-player-shown'); }
                    if (panel) panel.classList.remove('visible');
                } else if (currentGlobalTrack()) {
                    window.showGlobalPlayer();
                }
            };
            if (!window.globalPlayerProgressTimer) {
                window.globalPlayerProgressTimer = setInterval(function() {
                    if (document.getElementById('singgharaGlobalPlayer')) window.updateGlobalPlayerProgress();
                }, 500);
            }
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function() { setTimeout(initSinggharaMediaHub, 300); });
        } else {
            setTimeout(initSinggharaMediaHub, 300);
        }
        """
    }
}
