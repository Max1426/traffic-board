using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
namespace TrafficBoard.Core;

public interface IBoardStore { BoardData Read(); void Write(BoardData data); }
public sealed class EncryptedBoardStore(string? directory = null) : IBoardStore
{
    public string FilePath { get; } = Path.Combine(directory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TrafficBoard"), "board.v1.enc");
    private static readonly byte[] Entropy = "TrafficBoard.Windows.Data.v1"u8.ToArray();
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = false, Converters = { new JsonStringEnumConverter() }, IgnoreReadOnlyProperties = true };
    public BoardData Read()
    {
        if (!File.Exists(FilePath)) return new();
        var bytes = File.ReadAllBytes(FilePath);
        if (bytes.Length < 2 || bytes[0] != 1) throw new InvalidDataException("Unknown encrypted format");
        var plain = ProtectedData.Unprotect(bytes[1..], Entropy, DataProtectionScope.CurrentUser);
        try
        {
            var data = JsonSerializer.Deserialize<BoardData>(plain, Options) ?? throw new InvalidDataException();
            if (data.Version != 1 || data.Subscriptions == null || data.Settings == null || data.Preferences == null || data.Subscriptions.Any(s => string.IsNullOrEmpty(s.Id) || s.Name == null || s.Url == null || s.UserAgent == null || s.Alerts == null)) throw new InvalidDataException("Unsupported data");
            if (InputRules.Thresholds(string.Join(',', data.Settings.UsageThresholds), true) == null || InputRules.Thresholds(string.Join(',', data.Settings.ExpiryDays), false) == null) throw new InvalidDataException("Invalid settings");
            return data;
        }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }
    public void Write(BoardData data)
    {
        var plain = JsonSerializer.SerializeToUtf8Bytes(data, Options);
        byte[] encrypted;
        try { encrypted = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser); }
        finally { CryptographicOperations.ZeroMemory(plain); }
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        var temp = FilePath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var file = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough)) { file.WriteByte(1); file.Write(encrypted); file.Flush(true); }
            if (File.Exists(FilePath)) File.Replace(temp, FilePath, null); else File.Move(temp, FilePath);
        }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
}
