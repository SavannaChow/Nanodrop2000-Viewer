using System;

namespace NanodropViewer.Core;

public sealed class TbwkException : Exception
{
    public TbwkException(string message) : base(message)
    {
    }
}
