namespace OpenAutoLink.Core.Diagnostics;

public static class OalLogger
{
    public static void Info(string message) => Console.WriteLine($"[OAL.DotNet][INFO] {message}");

    public static void Warn(string message) => Console.WriteLine($"[OAL.DotNet][WARN] {message}");

    public static void Error(string message) => Console.WriteLine($"[OAL.DotNet][ERROR] {message}");
}
