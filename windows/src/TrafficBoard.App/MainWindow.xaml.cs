using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.Windows.AppLifecycle;
using Microsoft.Windows.AppNotifications;
using Microsoft.UI.Windowing;
using Windows.ApplicationModel.DataTransfer;
using Windows.UI.ViewManagement;
using TrafficBoard.Core;
using System.Collections.Concurrent;
namespace TrafficBoard.App;
public sealed partial class MainWindow : Window
{
    private readonly BoardViewModel vm = new();
    private readonly SystemClock clock = new();
    private readonly SubscriptionClient source = new();
    private readonly WindowsNotifications notifications = new();
    private readonly BoardRepository repository;
    private readonly RefreshScheduler scheduler;
    private readonly NativeWindow native;
    private readonly ConcurrentDictionary<int, Task> operations = new();
    private int operationId;
    private string? selectedId;
    private bool settingsLoading, closing, allowClose, dialogOpen;
    public MainWindow()
    {
        InitializeComponent();
        repository = new(new EncryptedBoardStore(), source, notifications, clock);
        scheduler = new(repository, clock);
        Root.DataContext = vm;
        ExtendsContentIntoTitleBar = true; SetTitleBar(TitleBar);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "traffic-board.ico"));
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        native = new(hwnd, () => _ = scheduler.CheckAsync(true), () => DispatcherQueue.TryEnqueue(() => { ConfigureBackdrop(); ApplyCaptionTheme(); }));
        var scale = NativeWindow.GetDpiForWindow(hwnd) / 96.0;
        var work = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Nearest).WorkArea;
        var width = Math.Min((int)(1120 * scale), work.Width - 40);
        var height = Math.Min((int)(820 * scale), work.Height - 40);
        AppWindow.MoveAndResize(new(work.X + (work.Width - width) / 2, work.Y + (work.Height - height) / 2, width, height));
        AppWindow.TitleBar.ButtonBackgroundColor = Microsoft.UI.Colors.Transparent;
        AppWindow.TitleBar.ButtonInactiveBackgroundColor = Microsoft.UI.Colors.Transparent;
        Root.ActualThemeChanged += (_, _) => ApplyCaptionTheme();
        ConfigureBackdrop();
        repository.Changed += OnRepositoryChanged;
        scheduler.Error += message => DispatcherQueue.TryEnqueue(() => vm.ShowNotice(message));
        notifications.Invoked += ActivateSubscription;
        AppWindow.Closing += Window_Closing;
        Activated += (_, e) => { if (e.WindowActivationState != WindowActivationState.Deactivated && repository.Initialized && !closing) _ = scheduler.CheckAsync(true); };
        Navigation.SelectedItem = HomeItem;
        Root.Loaded += async (_, _) => { await repository.InitializeAsync(); ApplyPreferences(); Update(); scheduler.Start(); };
    }
    private void ConfigureBackdrop()
    {
        if (!NativeWindow.HighContrast && MicaController.IsSupported()) { SystemBackdrop = new MicaBackdrop(); Root.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent); }
        else { SystemBackdrop = null; Root.Background = (Brush)Application.Current.Resources["ApplicationPageBackgroundThemeBrush"]; }
    }
    private void ApplyCaptionTheme() => AppWindow.TitleBar.ButtonForegroundColor = Root.ActualTheme == ElementTheme.Dark ? Microsoft.UI.Colors.White : Microsoft.UI.Colors.Black;
    private void ApplyPreferences() { Root.RequestedTheme = repository.Data.Preferences.Theme switch { ThemeMode.Light => ElementTheme.Light, ThemeMode.Dark => ElementTheme.Dark, _ => ElementTheme.Default }; ApplyCaptionTheme(); }
    private void OnRepositoryChanged() => DispatcherQueue.TryEnqueue(Update);
    private void Update()
    {
        if (closing) return;
        vm.Update(repository, clock.Now);
        EmptyPanel.Visibility = vm.Empty ? Visibility.Visible : Visibility.Collapsed;
        if (selectedId != null && DetailPanel.Visibility == Visibility.Visible) ShowDetails(selectedId);
    }
    private Task RunAsync(Func<Task> action)
    {
        if (closing) return Task.CompletedTask;
        var id = Interlocked.Increment(ref operationId);
        async Task Execute()
        {
            try { await action(); }
            catch (OperationCanceledException) when (closing) { }
            catch (ArgumentException e) { vm.ShowNotice(e.Message); }
            catch { vm.ShowNotice("操作无法完成，可能是网络或本地存储不可用；原数据已保留。请稍后重试。"); }
        }
        var task = Execute(); operations[id] = task;
        _ = task.ContinueWith(_ => operations.TryRemove(id, out var removed), TaskScheduler.Default);
        return task;
    }
    private void Cards_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (CardLayout != null) CardLayout.MaximumRowsOrColumns = e.NewSize.Width >= 780 ? 2 : 1;
        if (Navigation != null) Navigation.PaneDisplayMode = Root.ActualWidth < 960 ? NavigationViewPaneDisplayMode.LeftCompact : NavigationViewPaneDisplayMode.Left;
    }
    private void Navigation_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (SettingsPanel == null) return;
        selectedId = null;
        HomePanel.Visibility = args.IsSettingsSelected ? Visibility.Collapsed : Visibility.Visible;
        SettingsPanel.Visibility = args.IsSettingsSelected ? Visibility.Visible : Visibility.Collapsed;
        DetailPanel.Visibility = Visibility.Collapsed;
        if (args.IsSettingsSelected) LoadSettings();
    }
    private void ShowHome() { selectedId = null; HomePanel.Visibility = Visibility.Visible; DetailPanel.Visibility = SettingsPanel.Visibility = Visibility.Collapsed; Navigation.SelectedItem = HomeItem; }
    private async void Add_Click(object sender, RoutedEventArgs e) => await EditSubscriptionAsync(null);
    private async void RefreshAll_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || !button.IsEnabled) return;
        button.IsEnabled = false;
        try { await RunAsync(() => repository.RefreshAllAsync(false, scheduler.Token)); }
        finally { button.IsEnabled = true; }
    }
    private async void Refresh_Click(object sender, RoutedEventArgs e) { if ((sender as FrameworkElement)?.Tag is string id) await RunAsync(() => repository.RefreshAsync(id, scheduler.Token)); }
    private void Detail_Click(object sender, RoutedEventArgs e) { if ((sender as FrameworkElement)?.Tag is string id) ShowDetails(id); }
    private static TextBlock Text(string text, double size = 14) => new() { Text = text, FontSize = size, TextWrapping = TextWrapping.Wrap };
    private StackPanel Field(string label, string value)
    {
        var result = new StackPanel { Spacing = 6 };
        var heading = Text(label, 12); heading.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        result.Children.Add(heading); var content = Text(value, 18); content.IsTextSelectionEnabled = true; result.Children.Add(content); return result;
    }
    private Button ActionButton(string label, Action action)
    {
        var button = new Button { Content = label }; button.Click += (_, _) => action(); return button;
    }
    private void ShowDetails(string id)
    {
        if (dialogOpen) return;
        var sub = repository.Data.Subscriptions.FirstOrDefault(s => s.Id == id);
        if (sub == null) { ShowHome(); return; }
        selectedId = id; HomePanel.Visibility = SettingsPanel.Visibility = Visibility.Collapsed; DetailPanel.Visibility = Visibility.Visible;
        DetailContent.Children.Clear();
        DetailContent.Children.Add(ActionButton("← 返回订阅", ShowHome));
        var heading = Text(sub.Name, 28); heading.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold; DetailContent.Children.Add(heading);
        DetailContent.Children.Add(Text(sub.Host));
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        var refresh = ActionButton(repository.IsRefreshing(id) ? "正在刷新…" : "刷新", () => _ = RunAsync(() => repository.RefreshAsync(id, scheduler.Token))); refresh.IsEnabled = !repository.IsRefreshing(id);
        actions.Children.Add(refresh); actions.Children.Add(ActionButton("编辑订阅", () => _ = EditSubscriptionAsync(sub))); actions.Children.Add(ActionButton("删除", () => _ = DeleteSubscriptionAsync(sub))); DetailContent.Children.Add(actions);
        var rows = new StackPanel { Spacing = 22 };
        rows.Children.Add(Field("已用 / 总额度", $"{Display.Bytes(sub.Info?.Used)} / {Display.Bytes(sub.Info?.Total)}"));
        rows.Children.Add(Field("剩余量 / 使用比例", $"{Display.Bytes(sub.Info?.Remaining)} / {Display.Percent(sub.Info?.Percent)}"));
        rows.Children.Add(new ProgressBar { Minimum = 0, Maximum = 100, Value = Math.Clamp(sub.Info?.Percent ?? 0, 0, 100), IsIndeterminate = repository.IsRefreshing(id) });
        rows.Children.Add(Field("上传", Display.Bytes(sub.Info?.Upload))); rows.Children.Add(Field("下载", Display.Bytes(sub.Info?.Download)));
        rows.Children.Add(Field("到期时间", Display.Date(sub.Info?.Expire, true))); rows.Children.Add(Field("最近成功获取", sub.LastSuccessAt == null ? "尚未成功获取" : Display.Date(sub.LastSuccessAt, true)));
        rows.Children.Add(Field("最近尝试刷新", sub.LastAttemptAt == null ? "尚未尝试" : Display.Date(sub.LastAttemptAt, true)));
        rows.Children.Add(Field(sub.Failed && sub.LastSuccessAt != null ? "刷新状态（保留上次成功数据）" : "刷新状态", Display.Status(sub.Status)));
        DetailContent.Children.Add(new Border { Style = (Style)Root.Resources["CardBorder"], Child = rows });
        var link = new StackPanel { Spacing = 12 };
        link.Children.Add(Text("订阅链接", 18)); link.Children.Add(Text("默认隐藏。显示或复制前请留意当前屏幕和剪贴板隐私。", 12));
        var reveal = new TextBox { Text = sub.Url, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        link.Children.Add(ActionButton("显示 / 隐藏完整链接", () => reveal.Visibility = reveal.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible));
        link.Children.Add(reveal);
        link.Children.Add(ActionButton("复制订阅链接", () => { try { var data = new DataPackage(); data.SetText(sub.Url); Clipboard.SetContent(data); vm.ShowNotice("订阅链接已复制到剪贴板"); } catch { vm.ShowNotice("无法访问剪贴板"); } }));
        link.Children.Add(Field("请求标识 User-Agent", sub.UserAgent));
        DetailContent.Children.Add(new Border { Style = (Style)Root.Resources["CardBorder"], Child = link });
    }
    private async Task EditSubscriptionAsync(Subscription? sub)
    {
        if (dialogOpen || closing || repository.StorageError != null) return;
        dialogOpen = true;
        var name = new TextBox { Header = "订阅名称", Text = sub?.Name ?? "", MaxLength = 100, PlaceholderText = "例如：日常订阅" };
        var url = new PasswordBox { Header = "HTTPS 订阅链接", Password = sub?.Url ?? "", MaxLength = 8192, PasswordRevealMode = PasswordRevealMode.Peek };
        var ua = new TextBox { Header = "User-Agent", Text = sub?.UserAgent ?? Subscription.DefaultUserAgent, MaxLength = 256 };
        var error = new InfoBar { Severity = InfoBarSeverity.Error, IsClosable = false };
        var content = new StackPanel { Spacing = 18, MinWidth = 360 }; content.Children.Add(name); content.Children.Add(url);
        content.Children.Add(new Expander { Header = "高级设置", Content = ua, HorizontalAlignment = HorizontalAlignment.Stretch });
        content.Children.Add(Text("链接只加密保存在当前 Windows 用户下，不会上传到额外服务器。", 12)); content.Children.Add(error);
        var dialog = new ContentDialog { XamlRoot = Root.XamlRoot, RequestedTheme = Root.ActualTheme, Title = sub == null ? "添加订阅" : "编辑订阅", Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto }, PrimaryButtonText = "保存并刷新", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Primary };
        string? savedId = null;
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            var deferral = args.GetDeferral(); dialog.IsPrimaryButtonEnabled = false;
            try { savedId = await repository.SaveSubscriptionAsync(sub?.Id, name.Text, url.Password, ua.Text); }
            catch (ArgumentException ex) { args.Cancel = true; error.Message = ex.Message; error.IsOpen = true; }
            catch { args.Cancel = true; error.Message = "无法保存到本地加密存储，原数据已保留"; error.IsOpen = true; }
            finally { dialog.IsPrimaryButtonEnabled = true; deferral.Complete(); }
        };
        try { await dialog.ShowAsync(); }
        finally { dialogOpen = false; }
        if (savedId != null) { ShowDetails(savedId); await RunAsync(() => repository.RefreshAsync(savedId, scheduler.Token)); }
    }
    private async Task DeleteSubscriptionAsync(Subscription sub)
    {
        if (dialogOpen) return;
        dialogOpen = true;
        var dialog = new ContentDialog { XamlRoot = Root.XamlRoot, RequestedTheme = Root.ActualTheme, Title = "删除订阅？", Content = $"将移除「{sub.Name}」和本机统计记录，不会影响服务商套餐。", PrimaryButtonText = "删除", CloseButtonText = "取消", DefaultButton = ContentDialogButton.Close };
        try { if (await dialog.ShowAsync() == ContentDialogResult.Primary) await RunAsync(async () => { await repository.DeleteAsync(sub.Id); ShowHome(); }); }
        finally { dialogOpen = false; }
    }
    private void LoadSettings()
    {
        settingsLoading = true;
        ThemeChoice.SelectedIndex = (int)repository.Data.Preferences.Theme;
        var s = repository.Data.Settings; UsageEnabled.IsOn = s.UsageEnabled; ExpiryEnabled.IsOn = s.ExpiryEnabled;
        UsageThresholds.Text = string.Join(", ", s.UsageThresholds); ExpiryThresholds.Text = string.Join(", ", s.ExpiryDays);
        NotificationStatus.Text = notifications.Enabled ? "Windows 通知已启用。专注模式仍可能隐藏横幅，应用内警示会保留。" : "Windows 通知当前不可用或已关闭。应用内仍显示警示，不消耗提醒记录。";
        settingsLoading = false;
    }
    private async void ThemeChoice_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (settingsLoading || repository == null || ThemeChoice.SelectedIndex < 0) return;
        await RunAsync(async () => { await repository.SavePreferencesAsync(new((ThemeMode)ThemeChoice.SelectedIndex)); ApplyPreferences(); });
    }
    private async void SaveSettings_Click(object sender, RoutedEventArgs e)
    {
        var usage = InputRules.Thresholds(UsageThresholds.Text, true); var days = InputRules.Thresholds(ExpiryThresholds.Text, false);
        if (usage == null || days == null) { vm.ShowNotice("请检查提醒阈值范围，使用逗号分隔，最多 8 个阈值"); return; }
        await RunAsync(async () => { await repository.SaveSettingsAsync(new() { UsageEnabled = UsageEnabled.IsOn, ExpiryEnabled = ExpiryEnabled.IsOn, UsageThresholds = usage, ExpiryDays = days }); vm.ShowNotice("提醒设置已保存"); });
    }
    private async void OpenNotifications_Click(object sender, RoutedEventArgs e) => await Windows.System.Launcher.LaunchUriAsync(new("ms-settings:notifications"));
    private void ActivateSubscription(string id) => DispatcherQueue.TryEnqueue(async () => { await repository.InitializeAsync(); if (closing) return; Activate(); ShowDetails(id); });
    public void HandleActivation(AppActivationArguments activation)
    {
        if (activation.Data is AppNotificationActivatedEventArgs notification && notification.Arguments.TryGetValue("subscription", out var id)) ActivateSubscription(id);
    }
    private async void Window_Closing(AppWindow sender, AppWindowClosingEventArgs e)
    {
        if (allowClose) return;
        e.Cancel = true; if (closing) return; closing = true; Root.IsHitTestVisible = false;
        await scheduler.DisposeAsync(); await Task.WhenAll(operations.Values.ToArray());
        await repository.DrainAsync();
        repository.Changed -= OnRepositoryChanged;
        native.Dispose(); notifications.Dispose(); source.Dispose();
        AppInstance.GetCurrent().UnregisterKey(); allowClose = true; Close();
    }
}
