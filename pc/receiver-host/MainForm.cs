using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace PhoneCamReceiver;

public sealed class MainForm : Form
{
    private readonly ShmWriter _shm;
    private readonly TcpStreamServer _server;
    private ReceiverSettings _settings;

    private readonly ComboBox _ipCombo;
    private readonly NumericUpDown _portInput;
    private readonly Button _applyNetBtn;
    private readonly Button _regenQrBtn;
    private readonly PictureBox _qrBox;
    private readonly TextBox _qrJsonBox;

    private readonly TabControl _rightTabs;
    private readonly TabPage _liveTab;
    private readonly TabPage _settingsTab;

    // Settings controls
    private readonly ComboBox _resolutionCombo;
    private readonly ComboBox _codecCombo;
    private readonly ComboBox _decoderCombo;
    private readonly ComboBox _gpuCombo;
    private readonly Button _refreshGpuBtn;
    private readonly CheckBox _flipHCheck;
    private readonly CheckBox _flipVCheck;
    private readonly Button _applySettingsBtn;
    private readonly Button _resetSettingsBtn;
    private readonly Label _settingsFeedbackLabel;

    // Live tab controls
    private readonly Button _flipHBtn;
    private readonly Button _flipVBtn;
    private readonly Button _pauseBtn;
    private readonly Button _openSettingsBtn;
    private readonly Label _quickConfigSummary;
    private readonly Button _registerBtn;
    private readonly Button _unregisterBtn;
    private readonly Label _vcamStatusLabel;

    private readonly Label _statusVal;
    private readonly Label _deviceVal;
    private readonly Label _fpsVal;
    private readonly Label _codecVal;
    private readonly Label _encoderVal;
    private readonly Label _decoderVal;

    private readonly VideoPreviewControl _previewControl;
    private readonly ContextMenuStrip _previewMenu;
    private readonly ToolStripMenuItem _menuTogglePreview;
    private readonly ToolStripMenuItem _menuFlipH;
    private readonly ToolStripMenuItem _menuFlipV;
    private readonly ToolStripMenuItem _menuTogglePause;
    private readonly TextBox _logBox;
    private readonly System.Windows.Forms.Timer _uiTimer;

    private string _currentToken;
    private bool _manualPaused;
    private volatile bool _previewEnabled = true;
    private bool _updatingFlipUi;
    private int _paintPending;
    private long _lastPreviewUpdateTs = Stopwatch.GetTimestamp() - Stopwatch.Frequency;

    private readonly record struct DecoderItem(DecoderMode Mode, string Label)
    {
        public override string ToString() => Label;
    }

    private readonly record struct CodecItem(CodecPreference Preference, string Label)
    {
        public override string ToString() => Label;
    }

    public MainForm()
    {
        Text = "Win10 PhoneCam Receiver - Win10 PhoneLink Virtual Camera";
        Size = new Size(1120, 770);
        MinimumSize = new Size(980, 670);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(24, 27, 34);
        ForeColor = Color.FromArgb(238, 241, 246);
        Font = new Font("Segoe UI", 9.5f);

        _settings = ReceiverSettings.Load();
        _previewEnabled = _settings.PreviewEnabled;
        List<GpuDeviceOption> gpuDevices = Protocol.EnumerateGpuDevices();
        GpuDeviceOption selectedGpu = gpuDevices.FirstOrDefault(g => g.DeviceIndex == _settings.GpuDeviceIndex)
            ?? gpuDevices.FirstOrDefault()
            ?? new GpuDeviceOption(-1, "Auto (시스템 기본 GPU)", false);
        _settings.GpuDeviceIndex = selectedGpu.DeviceIndex;

        ResolutionOption selectedRes = Protocol.FindResolutionOption(_settings.ResolutionId);
        _settings.ResolutionId = selectedRes.Id;

        _currentToken = Protocol.GenerateHexToken16();
        _shm = new ShmWriter();
        _server = new TcpStreamServer(
            _shm,
            _settings.Port,
            _currentToken,
            _settings.Decoder,
            AppendLog,
            gpuDeviceIndex: _settings.GpuDeviceIndex,
            gpuDeviceName: selectedGpu.Name,
            codecPreference: _settings.Codec,
            targetWidth: selectedRes.Width,
            targetHeight: selectedRes.Height,
            flipHorizontal: _settings.FlipHorizontal,
            flipVertical: _settings.FlipVertical);
        _shm.SetFlipFlags(_settings.FlipHorizontal, _settings.FlipVertical, preApplied: true, persistToRegistry: true);
        _server.FrameAvailable += OnFrameAvailable;

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 2,
            Padding = new Padding(12),
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 335));
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 135));
        Controls.Add(root);

        // Left Panel: QR Pairing & Network + VCam Registration
        var leftPanel = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(32, 36, 46),
            Padding = new Padding(12),
            Margin = new Padding(0, 0, 10, 8),
        };
        root.Controls.Add(leftPanel, 0, 0);

        var leftTitle = new Label
        {
            Text = "QR Pairing & Network",
            Font = new Font("Segoe UI Semibold", 11f),
            Dock = DockStyle.Top,
            Height = 26,
        };

        _qrBox = new PictureBox
        {
            Width = 260,
            Height = 260,
            SizeMode = PictureBoxSizeMode.Zoom,
            BackColor = Color.White,
            Location = new Point(25, 38),
        };

        var ipLabel = new Label { Text = "PC LAN IP", Location = new Point(12, 310), AutoSize = true, ForeColor = Color.LightGray };
        _ipCombo = new ComboBox
        {
            Location = new Point(12, 330),
            Width = 286,
            DropDownStyle = ComboBoxStyle.DropDownList,
            BackColor = Color.FromArgb(44, 49, 62),
            ForeColor = Color.White,
        };
        foreach (string ip in Protocol.EnumerateLanIps())
        {
            _ipCombo.Items.Add(ip);
        }
        if (_ipCombo.Items.Count > 0) _ipCombo.SelectedIndex = 0;
        _ipCombo.SelectedIndexChanged += (_, _) => UpdateQrDisplay();

        var portLabel = new Label { Text = "TCP Port", Location = new Point(12, 364), AutoSize = true, ForeColor = Color.LightGray };
        _portInput = new NumericUpDown
        {
            Location = new Point(12, 384),
            Width = 100,
            Minimum = 1024,
            Maximum = 65535,
            Value = Math.Clamp(_settings.Port, 1024, 65535),
            BackColor = Color.FromArgb(44, 49, 62),
            ForeColor = Color.White,
        };

        _applyNetBtn = MakeButton("Apply Port", new Point(120, 382), 84, Color.FromArgb(52, 108, 196));
        _applyNetBtn.Click += (_, _) => ApplyNetworkSettings();

        _regenQrBtn = MakeButton("Regenerate QR", new Point(210, 382), 92, Color.FromArgb(62, 70, 88));
        _regenQrBtn.Click += (_, _) => RegenerateQr();

        var jsonLabel = new Label { Text = "Pairing Payload (Auto-Syncs Codec/Res)", Location = new Point(12, 418), AutoSize = true, ForeColor = Color.LightGray };
        _qrJsonBox = new TextBox
        {
            Location = new Point(12, 438),
            Width = 286,
            ReadOnly = true,
            BackColor = Color.FromArgb(24, 27, 34),
            ForeColor = Color.FromArgb(165, 214, 255),
            BorderStyle = BorderStyle.FixedSingle,
        };

        var vcamTitle = new Label
        {
            Text = "DirectShow Virtual Camera",
            Font = new Font("Segoe UI Semibold", 10f),
            Location = new Point(12, 474),
            AutoSize = true,
        };
        _vcamStatusLabel = new Label
        {
            Text = "Checking registration...",
            Location = new Point(12, 497),
            Width = 286,
            AutoEllipsis = true,
            ForeColor = Color.LightGray,
        };
        _registerBtn = MakeButton("Register VCam", new Point(12, 522), 138, Color.FromArgb(46, 139, 87));
        _registerBtn.Click += (_, _) => HandleVcamRegister(unregister: false);

        _unregisterBtn = MakeButton("Unregister VCam", new Point(160, 522), 138, Color.FromArgb(168, 66, 66));
        _unregisterBtn.Click += (_, _) => HandleVcamRegister(unregister: true);

        leftPanel.Controls.AddRange(
        [
            leftTitle, _qrBox, ipLabel, _ipCombo, portLabel, _portInput,
            _applyNetBtn, _regenQrBtn, jsonLabel, _qrJsonBox,
            vcamTitle, _vcamStatusLabel, _registerBtn, _unregisterBtn
        ]);

        // Right Area: TabControl (Live Stream | Settings)
        _rightTabs = new TabControl
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0, 0, 0, 8),
        };
        root.Controls.Add(_rightTabs, 1, 0);

        _liveTab = new TabPage("실시간 화면 (Live Stream)")
        {
            BackColor = Color.FromArgb(24, 27, 34),
            Padding = new Padding(6),
        };
        _settingsTab = new TabPage("설정 (해상도 / 코덱 / 디코더 장치)")
        {
            BackColor = Color.FromArgb(24, 27, 34),
            Padding = new Padding(10),
        };
        _rightTabs.TabPages.Add(_liveTab);
        _rightTabs.TabPages.Add(_settingsTab);

        // --- Tab 1: Live Stream ---
        var rightPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 3,
            ColumnCount = 1,
        };
        rightPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        rightPanel.RowStyles.Add(new RowStyle(SizeType.Absolute, 94));
        rightPanel.RowStyles.Add(new RowStyle(SizeType.Absolute, 50));
        _liveTab.Controls.Add(rightPanel);

        _previewControl = new VideoPreviewControl
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0, 0, 0, 8),
        };
        _previewControl.SetPreviewEnabled(_previewEnabled);

        _menuTogglePreview = new ToolStripMenuItem();
        _menuTogglePreview.Click += (_, _) => SetPreviewEnabled(!_previewEnabled);

        _menuFlipH = new ToolStripMenuItem("좌/우 반전 (Horizontal Flip)");
        _menuFlipH.Click += (_, _) => SetFlipState(!_settings.FlipHorizontal, _settings.FlipVertical);

        _menuFlipV = new ToolStripMenuItem("상/하 반전 (Vertical Flip)");
        _menuFlipV.Click += (_, _) => SetFlipState(_settings.FlipHorizontal, !_settings.FlipVertical);

        _menuTogglePause = new ToolStripMenuItem();
        _menuTogglePause.Click += (_, _) => TogglePause();

        _previewMenu = new ContextMenuStrip();
        _previewMenu.Opening += (_, _) =>
        {
            _menuTogglePreview.Text = _previewEnabled
                ? "실시간 미리보기 화면 끄기 - 리소스 절감 (Preview OFF)"
                : "실시간 미리보기 화면 켜기 (Preview ON)";
            _menuTogglePreview.Checked = _previewEnabled;
            _menuFlipH.Checked = _settings.FlipHorizontal;
            _menuFlipV.Checked = _settings.FlipVertical;
            _menuTogglePause.Text = _manualPaused
                ? "가상 카메라 재개 (Resume Video)"
                : "가상 카메라 일시중지 (Privacy Pause)";
        };
        _previewMenu.Items.AddRange([_menuTogglePreview, new ToolStripSeparator(), _menuFlipH, _menuFlipV, new ToolStripSeparator(), _menuTogglePause]);
        _previewControl.ContextMenuStrip = _previewMenu;
        _liveTab.ContextMenuStrip = _previewMenu;

        rightPanel.Controls.Add(_previewControl, 0, 0);

        var telemetryPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(32, 36, 46),
            ColumnCount = 4,
            RowCount = 3,
            Padding = new Padding(10, 6, 10, 6),
            Margin = new Padding(0, 0, 0, 8),
        };
        telemetryPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 118));
        telemetryPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        telemetryPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 118));
        telemetryPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        for (int r = 0; r < 3; r++) telemetryPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 33.3f));

        _statusVal = AddTelemetryPair(telemetryPanel, 0, 0, "Status:", "Listening");
        _deviceVal = AddTelemetryPair(telemetryPanel, 0, 2, "Connected Device:", "Waiting...");
        _fpsVal = AddTelemetryPair(telemetryPanel, 1, 0, "Live FPS:", "0.0 fps");
        _codecVal = AddTelemetryPair(telemetryPanel, 1, 2, "Codec / Res:", "-");
        _encoderVal = AddTelemetryPair(telemetryPanel, 2, 0, "Phone Encoder:", "-");
        _decoderVal = AddTelemetryPair(telemetryPanel, 2, 2, "Active Decoder:", Protocol.FormatDecoderDisplayName(_settings.Decoder, "h264", _settings.GpuDeviceIndex, selectedGpu.Name));
        rightPanel.Controls.Add(telemetryPanel, 0, 1);

        var quickBar = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = Color.FromArgb(32, 36, 46),
            Padding = new Padding(8, 6, 8, 6),
            ColumnCount = 5,
            RowCount = 1,
        };
        quickBar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        quickBar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        quickBar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        quickBar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        quickBar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        quickBar.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        _quickConfigSummary = new Label
        {
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            AutoEllipsis = true,
            ForeColor = Color.FromArgb(180, 210, 255),
            Text = string.Empty,
        };
        _flipHBtn = MakeButton("좌/우 반전: OFF", Point.Empty, 108, Color.FromArgb(62, 70, 88));
        _flipHBtn.Height = 30;
        _flipHBtn.Margin = new Padding(4, 3, 0, 3);
        _flipHBtn.Click += (_, _) => SetFlipState(!_settings.FlipHorizontal, _settings.FlipVertical);

        _flipVBtn = MakeButton("상/하 반전: OFF", Point.Empty, 108, Color.FromArgb(62, 70, 88));
        _flipVBtn.Height = 30;
        _flipVBtn.Margin = new Padding(4, 3, 0, 3);
        _flipVBtn.Click += (_, _) => SetFlipState(_settings.FlipHorizontal, !_settings.FlipVertical);

        _openSettingsBtn = MakeButton("설정 열기", Point.Empty, 90, Color.FromArgb(52, 108, 196));
        _openSettingsBtn.Height = 30;
        _openSettingsBtn.Margin = new Padding(6, 3, 0, 3);
        _openSettingsBtn.Click += (_, _) => _rightTabs.SelectedTab = _settingsTab;

        _pauseBtn = MakeButton("Privacy Pause", Point.Empty, 108, Color.FromArgb(180, 120, 32));
        _pauseBtn.Height = 30;
        _pauseBtn.Margin = new Padding(6, 3, 0, 3);
        _pauseBtn.Click += (_, _) => TogglePause();

        quickBar.Controls.Add(_quickConfigSummary, 0, 0);
        quickBar.Controls.Add(_flipHBtn, 1, 0);
        quickBar.Controls.Add(_flipVBtn, 2, 0);
        quickBar.Controls.Add(_openSettingsBtn, 3, 0);
        quickBar.Controls.Add(_pauseBtn, 4, 0);
        rightPanel.Controls.Add(quickBar, 0, 2);

        // --- Tab 2: Dedicated Settings Page (Resolution / Codec / Decoder Device) ---
        var settingsScroll = new Panel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            BackColor = Color.FromArgb(24, 27, 34),
        };
        _settingsTab.Controls.Add(settingsScroll);

        var settingsStack = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoSize = true,
            Dock = DockStyle.Top,
            Padding = new Padding(4),
        };
        settingsScroll.Controls.Add(settingsStack);

        var settingsTitle = new Label
        {
            Text = "스트리밍 및 하드웨어 가속 설정",
            Font = new Font("Segoe UI Semibold", 13f),
            ForeColor = Color.White,
            AutoSize = true,
            Margin = new Padding(2, 2, 2, 10),
        };
        settingsStack.Controls.Add(settingsTitle);

        // Card 1: Resolution Settings
        GroupBox resCard = MakeSettingsCard("1. 해상도 설정 (Resolution Setting)", 700, 108);
        var resLbl = new Label { Text = "출력 / 목표 해상도:", Location = new Point(16, 30), AutoSize = true, ForeColor = Color.LightGray };
        _resolutionCombo = new ComboBox
        {
            Location = new Point(170, 27),
            Width = 505,
            DropDownStyle = ComboBoxStyle.DropDownList,
            BackColor = Color.FromArgb(44, 49, 62),
            ForeColor = Color.White,
        };
        foreach (ResolutionOption opt in Protocol.ResolutionPresets)
        {
            _resolutionCombo.Items.Add(opt);
        }
        _resolutionCombo.SelectedItem = selectedRes;
        var resHint = new Label
        {
            Text = "선택한 해상도가 QR 페어링 정보에 포함되어 스마트폰 앱과 자동 동기화되며, PC 가상 카메라 출력 크기로 적용됩니다.",
            Location = new Point(16, 64),
            Width = 665,
            Height = 32,
            ForeColor = Color.FromArgb(165, 175, 195),
        };
        resCard.Controls.AddRange([resLbl, _resolutionCombo, resHint]);
        settingsStack.Controls.Add(resCard);

        // Card 2: Codec Settings
        GroupBox codecCard = MakeSettingsCard("2. 코덱 설정 (Video Codec Setting)", 700, 108);
        var codecLbl = new Label { Text = "비디오 전송 코덱:", Location = new Point(16, 30), AutoSize = true, ForeColor = Color.LightGray };
        _codecCombo = new ComboBox
        {
            Location = new Point(170, 27),
            Width = 505,
            DropDownStyle = ComboBoxStyle.DropDownList,
            BackColor = Color.FromArgb(44, 49, 62),
            ForeColor = Color.White,
        };
        _codecCombo.Items.Add(new CodecItem(CodecPreference.H264, "H.264 / AVC (권장 - 최저 지연 및 최대 호환성)"));
        _codecCombo.Items.Add(new CodecItem(CodecPreference.Hevc, "HEVC / H.265 (고화질 · Wi-Fi 대역폭 절감)"));
        _codecCombo.Items.Add(new CodecItem(CodecPreference.Auto, "Auto (휴대폰에서 선택한 코덱 자동 감지)"));
        SelectCodecItem(_settings.Codec);
        var codecHint = new Label
        {
            Text = "H.264 또는 HEVC 지정 시 QR 스캔만으로 스마트폰 하드웨어 인코더와 PC CUVID/D3D11VA 디코더 코덱을 일치시킵니다.",
            Location = new Point(16, 64),
            Width = 665,
            Height = 32,
            ForeColor = Color.FromArgb(165, 175, 195),
        };
        codecCard.Controls.AddRange([codecLbl, _codecCombo, codecHint]);
        settingsStack.Controls.Add(codecCard);

        // Card 3: Decoder Device Settings
        GroupBox decCard = MakeSettingsCard("3. 디코더 장치 설정 (Decoder Backend & GPU Hardware)", 700, 148);
        var decModeLbl = new Label { Text = "디코딩 가속 방식:", Location = new Point(16, 32), AutoSize = true, ForeColor = Color.LightGray };
        _decoderCombo = new ComboBox
        {
            Location = new Point(170, 29),
            Width = 505,
            DropDownStyle = ComboBoxStyle.DropDownList,
            BackColor = Color.FromArgb(44, 49, 62),
            ForeColor = Color.White,
        };
        _decoderCombo.Items.Add(new DecoderItem(DecoderMode.Nvdec, "NVIDIA NVDEC (GPU 하드웨어 가속 - h264_cuvid / hevc_cuvid)"));
        _decoderCombo.Items.Add(new DecoderItem(DecoderMode.D3d11va, "Direct3D 11 (GPU 하드웨어 가속 - d3d11va)"));
        _decoderCombo.Items.Add(new DecoderItem(DecoderMode.Cpu, "Software CPU (FFmpeg CPU 디코더)"));
        SelectDecoderItem(_settings.Decoder);

        var gpuLbl = new Label { Text = "디코더 GPU 장치:", Location = new Point(16, 70), AutoSize = true, ForeColor = Color.LightGray };
        _gpuCombo = new ComboBox
        {
            Location = new Point(170, 67),
            Width = 415,
            DropDownStyle = ComboBoxStyle.DropDownList,
            BackColor = Color.FromArgb(44, 49, 62),
            ForeColor = Color.White,
        };
        PopulateGpuCombo(gpuDevices, _settings.GpuDeviceIndex);

        _refreshGpuBtn = MakeButton("장치 새로고침", new Point(591, 66), 84, Color.FromArgb(62, 70, 88));
        _refreshGpuBtn.Click += (_, _) =>
        {
            int curIdx = (_gpuCombo.SelectedItem as GpuDeviceOption)?.DeviceIndex ?? _settings.GpuDeviceIndex;
            PopulateGpuCombo(Protocol.EnumerateGpuDevices(), curIdx);
        };

        var decHint = new Label
        {
            Text = "NVIDIA GPU(RTX 시리즈 등) 환경에서는 NVDEC(CUDA) 선택 시 CPU 부하 없이 고속 디코딩됩니다. CPU 모드에서는 GPU 선택이 비활성화됩니다.",
            Location = new Point(16, 104),
            Width = 665,
            Height = 34,
            ForeColor = Color.FromArgb(165, 175, 195),
        };
        _decoderCombo.SelectedIndexChanged += (_, _) => UpdateGpuComboEnabled();
        decCard.Controls.AddRange([decModeLbl, _decoderCombo, gpuLbl, _gpuCombo, _refreshGpuBtn, decHint]);
        settingsStack.Controls.Add(decCard);

        // Card 4: Virtual Camera Flip Settings
        GroupBox flipCard = MakeSettingsCard("4. 가상 카메라 화면 반전 (상/하/좌/우 반전)", 700, 102);
        _flipHCheck = new CheckBox
        {
            Text = "좌/우 반전 (Horizontal Flip)",
            Location = new Point(16, 30),
            AutoSize = true,
            ForeColor = Color.White,
            Checked = _settings.FlipHorizontal,
        };
        _flipVCheck = new CheckBox
        {
            Text = "상/하 반전 (Vertical Flip)",
            Location = new Point(280, 30),
            AutoSize = true,
            ForeColor = Color.White,
            Checked = _settings.FlipVertical,
        };
        _flipHCheck.CheckedChanged += (_, _) =>
        {
            if (!_updatingFlipUi) SetFlipState(_flipHCheck.Checked, _flipVCheck.Checked);
        };
        _flipVCheck.CheckedChanged += (_, _) =>
        {
            if (!_updatingFlipUi) SetFlipState(_flipHCheck.Checked, _flipVCheck.Checked);
        };
        var flipHint = new Label
        {
            Text = "대기 화면(기본값 RAW 정방향)과 송출 영상의 좌/우·상/하 반전을 즉시 전환합니다. (Discord 자체 좌우반전이 보이는 경우 좌/우 반전을 켜세요)",
            Location = new Point(16, 62),
            Width = 665,
            Height = 32,
            ForeColor = Color.FromArgb(165, 175, 195),
        };
        flipCard.Controls.AddRange([_flipHCheck, _flipVCheck, flipHint]);
        settingsStack.Controls.Add(flipCard);

        // Settings Action Buttons
        var actionPanel = new Panel
        {
            Width = 700,
            Height = 46,
            Margin = new Padding(2, 6, 2, 4),
        };
        _applySettingsBtn = MakeButton("설정 저장 및 적용 (Apply & Save)", new Point(0, 6), 220, Color.FromArgb(46, 139, 87));
        _applySettingsBtn.Height = 34;
        _applySettingsBtn.Click += (_, _) => ApplyAndSaveSettings();

        _resetSettingsBtn = MakeButton("기본값 복원", new Point(232, 6), 120, Color.FromArgb(62, 70, 88));
        _resetSettingsBtn.Height = 34;
        _resetSettingsBtn.Click += (_, _) => ResetDefaultSettings();

        _settingsFeedbackLabel = new Label
        {
            Location = new Point(366, 14),
            Width = 330,
            ForeColor = Color.LightGreen,
            Text = string.Empty,
        };
        actionPanel.Controls.AddRange([_applySettingsBtn, _resetSettingsBtn, _settingsFeedbackLabel]);
        settingsStack.Controls.Add(actionPanel);

        // Bottom Log Area
        _logBox = new TextBox
        {
            Dock = DockStyle.Fill,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            BackColor = Color.FromArgb(18, 20, 26),
            ForeColor = Color.FromArgb(190, 200, 215),
            Font = new Font("Consolas", 8.5f),
            BorderStyle = BorderStyle.FixedSingle,
        };
        root.SetColumnSpan(_logBox, 2);
        root.Controls.Add(_logBox, 0, 1);

        _uiTimer = new System.Windows.Forms.Timer { Interval = 250 };
        _uiTimer.Tick += (_, _) => RefreshTelemetry();

        UpdateGpuComboEnabled();
        UpdateFlipUiState();
        UpdateQuickSummary();

        Load += (_, _) =>
        {
            UpdateQrDisplay();
            RefreshVcamRegistrationStatus();
            _server.Start();
            _uiTimer.Start();
        };
        Shown += (_, _) =>
        {
            if (VcamRegistration.FindVcamDllPath() != null && !VcamRegistration.IsRegisteredInHklm(out _))
            {
                (bool ok, string msg) = VcamRegistration.Register();
                AppendLog($"[VCam] Auto-register HKLM: {msg}");
                RefreshVcamRegistrationStatus();
                if (!ok)
                {
                    AppendLog("[VCam] Click 'Register VCam' with Administrator rights so Discord detects the camera.");
                }
            }
        };
        FormClosing += (_, _) =>
        {
            _uiTimer.Stop();
            _server.FrameAvailable -= OnFrameAvailable;
            _server.Dispose();
            _shm.Dispose();
            _previewMenu.Dispose();
            _qrBox.Image?.Dispose();
        };
    }

    private static GroupBox MakeSettingsCard(string title, int width, int height)
    {
        return new GroupBox
        {
            Text = title,
            Width = width,
            Height = height,
            BackColor = Color.FromArgb(32, 36, 46),
            ForeColor = Color.FromArgb(145, 200, 255),
            Font = new Font("Segoe UI Semibold", 9.5f),
            Margin = new Padding(2, 0, 2, 10),
        };
    }

    private void PopulateGpuCombo(List<GpuDeviceOption> devices, int preferredIndex)
    {
        _gpuCombo.Items.Clear();
        foreach (GpuDeviceOption d in devices)
        {
            _gpuCombo.Items.Add(d);
        }
        GpuDeviceOption? match = devices.FirstOrDefault(d => d.DeviceIndex == preferredIndex) ?? devices.FirstOrDefault();
        if (match != null)
        {
            _gpuCombo.SelectedItem = match;
        }
    }

    private void SelectCodecItem(CodecPreference pref)
    {
        for (int i = 0; i < _codecCombo.Items.Count; i++)
        {
            if (_codecCombo.Items[i] is CodecItem item && item.Preference == pref)
            {
                _codecCombo.SelectedIndex = i;
                return;
            }
        }
        if (_codecCombo.Items.Count > 0) _codecCombo.SelectedIndex = 0;
    }

    private void SelectDecoderItem(DecoderMode mode)
    {
        for (int i = 0; i < _decoderCombo.Items.Count; i++)
        {
            if (_decoderCombo.Items[i] is DecoderItem item && item.Mode == mode)
            {
                _decoderCombo.SelectedIndex = i;
                return;
            }
        }
        if (_decoderCombo.Items.Count > 0) _decoderCombo.SelectedIndex = 0;
    }

    private void UpdateGpuComboEnabled()
    {
        bool useGpu = (_decoderCombo.SelectedItem as DecoderItem?)?.Mode != DecoderMode.Cpu;
        _gpuCombo.Enabled = useGpu;
        _refreshGpuBtn.Enabled = useGpu;
    }

    private void ApplyAndSaveSettings()
    {
        ResolutionOption res = (_resolutionCombo.SelectedItem as ResolutionOption) ?? Protocol.ResolutionPresets[0];
        CodecPreference codec = (_codecCombo.SelectedItem as CodecItem?)?.Preference ?? CodecPreference.H264;
        DecoderMode decoder = (_decoderCombo.SelectedItem as DecoderItem?)?.Mode ?? DecoderMode.Nvdec;
        GpuDeviceOption gpu = (_gpuCombo.SelectedItem as GpuDeviceOption) ?? new GpuDeviceOption(-1, "Auto", false);

        _settings.ResolutionId = res.Id;
        _settings.Codec = codec;
        _settings.Decoder = decoder;
        _settings.GpuDeviceIndex = gpu.DeviceIndex;
        _settings.FlipHorizontal = _flipHCheck.Checked;
        _settings.FlipVertical = _flipVCheck.Checked;
        _settings.Port = (int)_portInput.Value;
        _settings.Save();

        _server.SetTargetResolution(res.Width, res.Height);
        _server.SetCodecPreference(codec);
        _server.SetDecoderConfig(decoder, gpu.DeviceIndex, gpu.Name);
        _server.SetFlip(_settings.FlipHorizontal, _settings.FlipVertical, persistToRegistry: true);

        UpdateFlipUiState();
        UpdateQrDisplay();
        UpdateQuickSummary();
        RefreshTelemetry();

        _settingsFeedbackLabel.Text = "설정이 저장되고 즉시 적용되었습니다.";
        AppendLog($"[Settings] Saved: res={res.Id}, codec={codec}, decoder={decoder}, gpu={gpu.DeviceIndex}, flipH={_settings.FlipHorizontal}, flipV={_settings.FlipVertical}");
    }

    private void ResetDefaultSettings()
    {
        _resolutionCombo.SelectedItem = Protocol.FindResolutionOption("1280x720");
        SelectCodecItem(CodecPreference.H264);
        SelectDecoderItem(DecoderMode.Nvdec);
        List<GpuDeviceOption> devices = Protocol.EnumerateGpuDevices();
        PopulateGpuCombo(devices, devices.FirstOrDefault(d => d.IsCudaCapable)?.DeviceIndex ?? -1);
        _updatingFlipUi = true;
        _flipHCheck.Checked = false;
        _flipVCheck.Checked = false;
        _updatingFlipUi = false;
        UpdateGpuComboEnabled();
        ApplyAndSaveSettings();
        _settingsFeedbackLabel.Text = "기본 설정으로 복원되었습니다.";
    }

    private void SetFlipState(bool flipH, bool flipV)
    {
        if (_settings.FlipHorizontal == flipH && _settings.FlipVertical == flipV) return;
        _settings.FlipHorizontal = flipH;
        _settings.FlipVertical = flipV;
        _settings.Save();
        _server.SetFlip(flipH, flipV, persistToRegistry: true);
        UpdateFlipUiState();
        UpdateQuickSummary();
    }

    private void UpdateFlipUiState()
    {
        bool h = _settings.FlipHorizontal;
        bool v = _settings.FlipVertical;
        _updatingFlipUi = true;
        _flipHCheck.Checked = h;
        _flipVCheck.Checked = v;
        _updatingFlipUi = false;

        _flipHBtn.Text = h ? "좌/우 반전: ON" : "좌/우 반전: OFF";
        _flipHBtn.BackColor = h ? Color.FromArgb(46, 139, 87) : Color.FromArgb(62, 70, 88);
        _flipVBtn.Text = v ? "상/하 반전: ON" : "상/하 반전: OFF";
        _flipVBtn.BackColor = v ? Color.FromArgb(46, 139, 87) : Color.FromArgb(62, 70, 88);
    }

    private void UpdateQuickSummary()
    {
        ResolutionOption res = Protocol.FindResolutionOption(_settings.ResolutionId);
        string resTxt = res.Width > 0 ? $"{res.Width}×{res.Height}" : "Auto";
        string codecTxt = _settings.Codec switch
        {
            CodecPreference.H264 => "H.264",
            CodecPreference.Hevc => "HEVC",
            _ => "Auto",
        };
        string decTxt = _settings.Decoder switch
        {
            DecoderMode.Nvdec => $"NVDEC (GPU {_settings.GpuDeviceIndex})",
            DecoderMode.D3d11va => $"D3D11VA (GPU {_settings.GpuDeviceIndex})",
            _ => "CPU",
        };
        string flipTxt = (_settings.FlipHorizontal, _settings.FlipVertical) switch
        {
            (true, true) => "좌우+상하",
            (true, false) => "좌우",
            (false, true) => "상하",
            _ => "기본",
        };
        _quickConfigSummary.Text = $"{resTxt} | {codecTxt} | {decTxt} | 반전: {flipTxt} | 미리보기: {(_previewEnabled ? "ON" : "OFF")}";
    }

    private static Button MakeButton(string text, Point location, int width, Color backColor)
    {
        return new Button
        {
            Text = text,
            Location = location,
            Width = width,
            Height = 28,
            FlatStyle = FlatStyle.Flat,
            BackColor = backColor,
            ForeColor = Color.White,
            Cursor = Cursors.Hand,
        };
    }

    private static Label AddTelemetryPair(TableLayoutPanel panel, int row, int col, string caption, string initial)
    {
        var lbl = new Label
        {
            Text = caption,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = Color.FromArgb(160, 170, 188),
        };
        var val = new Label
        {
            Text = initial,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            AutoEllipsis = true,
            Font = new Font("Segoe UI Semibold", 9.5f),
            ForeColor = Color.White,
        };
        panel.Controls.Add(lbl, col, row);
        panel.Controls.Add(val, col + 1, row);
        return val;
    }

    private void UpdateQrDisplay()
    {
        string ip = _ipCombo.SelectedItem?.ToString() ?? "127.0.0.1";
        int port = (int)_portInput.Value;
        ResolutionOption res = Protocol.FindResolutionOption(_settings.ResolutionId);
        string json = Protocol.BuildQrJson(ip, port, _currentToken, null, _settings.Codec, res.Width, res.Height);
        _qrJsonBox.Text = json;

        Bitmap bmp = QrCodeGenerator.RenderBitmap(json, targetPixels: 260);
        Image? old = _qrBox.Image;
        _qrBox.Image = bmp;
        old?.Dispose();
    }

    private void ApplyNetworkSettings()
    {
        int newPort = (int)_portInput.Value;
        try
        {
            _settings.Port = newPort;
            _settings.Save();
            _server.Reconfigure(newPort, _currentToken);
            UpdateQrDisplay();
        }
        catch (Exception ex)
        {
            AppendLog($"[Error] Failed to bind port {newPort}: {ex.Message}");
            MessageBox.Show(this, $"Could not listen on TCP port {newPort}:\n{ex.Message}", "Port Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void RegenerateQr()
    {
        _currentToken = Protocol.GenerateHexToken16();
        _server.Reconfigure((int)_portInput.Value, _currentToken);
        UpdateQrDisplay();
        AppendLog("[Pairing] Generated new QR token.");
    }

    private void SetPreviewEnabled(bool enabled)
    {
        if (_previewEnabled == enabled) return;
        _previewEnabled = enabled;
        _settings.PreviewEnabled = enabled;
        _settings.Save();
        _previewControl.SetPreviewEnabled(enabled);
        UpdateQuickSummary();
        RefreshTelemetry();
        AppendLog(enabled
            ? "[Preview] 실시간 미리보기 켜짐 (ON)"
            : "[Preview] 실시간 미리보기 꺼짐 (OFF - CPU/메모리 리소스 절감 모드)");
    }

    private void TogglePause()
    {
        _manualPaused = !_manualPaused;
        _server.SetManualPaused(_manualPaused);
        _pauseBtn.Text = _manualPaused ? "Resume Video" : "Privacy Pause";
        _pauseBtn.BackColor = _manualPaused ? Color.FromArgb(46, 139, 87) : Color.FromArgb(180, 120, 32);
        _previewControl.SetOverlayState(connected: _server.GetTelemetry().Connected, paused: _manualPaused);
    }

    private void HandleVcamRegister(bool unregister)
    {
        (bool ok, string msg) = unregister ? VcamRegistration.Unregister() : VcamRegistration.Register();
        AppendLog($"[VCam] {msg}");
        RefreshVcamRegistrationStatus();
        if (!ok)
        {
            MessageBox.Show(this, msg, "Virtual Camera Registration", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void RefreshVcamRegistrationStatus()
    {
        if (VcamRegistration.IsRegisteredInHklm(out string? hklmPath))
        {
            _vcamStatusLabel.Text = $"Registered HKLM ({Path.GetFileName(hklmPath)})";
            _vcamStatusLabel.ForeColor = Color.LightGreen;
        }
        else if (VcamRegistration.IsRegistered(out string? hkcuPath))
        {
            _vcamStatusLabel.Text = $"HKCU Only ({Path.GetFileName(hkcuPath)} - Admin for Discord)";
            _vcamStatusLabel.ForeColor = Color.Gold;
        }
        else
        {
            string? found = VcamRegistration.FindVcamDllPath();
            _vcamStatusLabel.Text = found != null ? "Not Registered (DLL Ready)" : "Not Registered (DLL Missing)";
            _vcamStatusLabel.ForeColor = Color.Orange;
        }
    }

    private void OnFrameAvailable(ReadOnlySpan<byte> bgraFrame, int width, int height)
    {
        if (!_previewEnabled) return;
        if (Volatile.Read(ref _paintPending) != 0) return;

        long now = Stopwatch.GetTimestamp();
        long minInterval = Stopwatch.Frequency / 30;
        long last = Volatile.Read(ref _lastPreviewUpdateTs);
        if (now - last < minInterval) return;

        Volatile.Write(ref _lastPreviewUpdateTs, now);
        _previewControl.UpdateFrame(bgraFrame, width, height);
        if (Interlocked.Exchange(ref _paintPending, 1) == 0 && !IsDisposed && IsHandleCreated)
        {
            try
            {
                BeginInvoke(() =>
                {
                    Interlocked.Exchange(ref _paintPending, 0);
                    if (!IsDisposed) _previewControl.Invalidate();
                });
            }
            catch (ObjectDisposedException)
            {
                Interlocked.Exchange(ref _paintPending, 0);
            }
            catch (InvalidOperationException)
            {
                Interlocked.Exchange(ref _paintPending, 0);
            }
        }
    }

    private void RefreshTelemetry()
    {
        StreamTelemetry t = _server.GetTelemetry();
        _statusVal.Text = t.Paused ? "Paused (Privacy)" : (t.Connected ? "Streaming Live" : $"Listening :{_server.Port}");
        _statusVal.ForeColor = t.Paused ? Color.Gold : (t.Connected ? Color.LightGreen : Color.LightSkyBlue);
        _deviceVal.Text = t.DeviceName;
        _fpsVal.Text = $"{t.Fps:F1} fps ({t.TotalFrames} frames)";
        _codecVal.Text = t.Connected ? $"{t.Codec}  {t.Width}x{t.Height} ({t.RotationDegrees}°)" : "-";
        _encoderVal.Text = t.EncoderName;
        _decoderVal.Text = t.ActiveDecoder;
        _previewControl.SetOverlayState(t.Connected, t.Paused);
    }

    private void AppendLog(string message)
    {
        if (IsDisposed || !IsHandleCreated) return;
        try
        {
            BeginInvoke(() =>
            {
                if (IsDisposed) return;
                if (_logBox.TextLength > 16000)
                {
                    _logBox.Text = _logBox.Text[^8000..];
                }
                _logBox.AppendText($"[{DateTime.Now:HH:mm:ss}] {message}{Environment.NewLine}");
            });
        }
        catch (ObjectDisposedException)
        {
        }
        catch (InvalidOperationException)
        {
        }
    }

    private sealed class VideoPreviewControl : Control
    {
        private readonly object _bmpLock = new();
        private Bitmap? _bitmap;
        private bool _connected;
        private bool _paused;
        private bool _previewEnabled = true;

        public VideoPreviewControl()
        {
            SetStyle(
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.UserPaint |
                ControlStyles.OptimizedDoubleBuffer |
                ControlStyles.ResizeRedraw,
                true);
            BackColor = Color.FromArgb(14, 16, 22);
        }

        public void SetPreviewEnabled(bool enabled)
        {
            bool changed = _previewEnabled != enabled;
            _previewEnabled = enabled;
            if (!enabled)
            {
                lock (_bmpLock)
                {
                    _bitmap?.Dispose();
                    _bitmap = null;
                }
            }
            if (changed)
            {
                Invalidate();
            }
        }

        public void UpdateFrame(ReadOnlySpan<byte> bgra, int width, int height)
        {
            if (!_previewEnabled) return;
            lock (_bmpLock)
            {
                FrameTransformer.CopyBgra32ToBitmap(bgra, width, height, ref _bitmap);
            }
        }

        public void SetOverlayState(bool connected, bool paused)
        {
            if (_connected != connected || _paused != paused)
            {
                _connected = connected;
                _paused = paused;
                Invalidate();
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            Graphics g = e.Graphics;
            g.Clear(BackColor);

            if (_previewEnabled)
            {
                lock (_bmpLock)
                {
                    if (_connected && !_paused && _bitmap != null)
                    {
                        float scale = Math.Min((float)ClientSize.Width / _bitmap.Width, (float)ClientSize.Height / _bitmap.Height);
                        int drawW = Math.Max(1, (int)(_bitmap.Width * scale));
                        int drawH = Math.Max(1, (int)(_bitmap.Height * scale));
                        int x = (ClientSize.Width - drawW) / 2;
                        int y = (ClientSize.Height - drawH) / 2;
                        g.InterpolationMode = InterpolationMode.Bilinear;
                        g.DrawImage(_bitmap, x, y, drawW, drawH);

                        const string hint = "우클릭: 미리보기 OFF (리소스 절감)";
                        using var hintFont = new Font("Segoe UI", 8.5f);
                        SizeF hintSize = g.MeasureString(hint, hintFont);
                        var pillRect = new RectangleF(ClientSize.Width - hintSize.Width - 18, 8, hintSize.Width + 10, hintSize.Height + 6);
                        using var pillBg = new SolidBrush(Color.FromArgb(145, 14, 16, 22));
                        using var pillFg = new SolidBrush(Color.FromArgb(200, 210, 225));
                        g.FillRectangle(pillBg, pillRect);
                        g.DrawString(hint, hintFont, pillFg, pillRect.X + 5, pillRect.Y + 3);
                        return;
                    }
                }
            }

            string msg = !_previewEnabled
                ? (_connected && !_paused
                    ? "실시간 미리보기 꺼짐 (리소스 절감 모드)\n가상 카메라(Win10 PhoneLink Virtual Camera) 영상은 정상 출력 중입니다.\n[마우스 우클릭 → 실시간 미리보기 켜기]로 화면을 다시 표시할 수 있습니다."
                    : "실시간 미리보기 꺼짐 (리소스 절감 모드)\n[마우스 우클릭 → 실시간 미리보기 켜기]로 화면을 다시 표시할 수 있습니다.")
                : (_paused
                    ? "Privacy Pause Active\nVirtual camera is outputting standby frame."
                    : "Waiting for Android Companion...\nScan the QR code on the left with the Win10 PhoneCam app.\n(우클릭 메뉴로 미리보기 ON/OFF 전환 가능)");
            using var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            using var brush = new SolidBrush(Color.FromArgb(165, 175, 195));
            using var font = new Font("Segoe UI", 11f);
            g.DrawString(msg, font, brush, ClientRectangle, fmt);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                lock (_bmpLock)
                {
                    _bitmap?.Dispose();
                    _bitmap = null;
                }
            }
            base.Dispose(disposing);
        }
    }
}
