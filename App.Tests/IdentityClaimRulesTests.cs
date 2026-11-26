using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

/// <summary>
/// What a pharmacist may type in the personal window (1.0.101): the IIN with its control digit and a full name. Synthetic
/// numbers only. The server repeats the same rules (IdentityClaimRulesTest.kt keeps the same vectors).
/// </summary>
public sealed class IdentityClaimRulesTests
{
    [Theory]
    [InlineData("900115300013")]
    [InlineData("850615400016")]
    [InlineData("990303500014")]
    [InlineData("781122300017")]
    [InlineData("951212500015")]
    [InlineData("123456012344")]
    // The first weights give 10, the second set decides.
    [InlineData("123456000405")]
    public void A_valid_IIN_passes(string iin)
    {
        Assert.True(IdentityClaimRules.IsValidIin(iin));
        var check = IdentityClaimRules.CheckIin(iin);
        Assert.True(check.Ok);
        Assert.Equal(iin, check.Value);
        Assert.Null(check.Error);
    }

    [Theory]
    [InlineData("900115 300013")]
    [InlineData(" 900115300013 ")]
    [InlineData("900115-300013")]
    [InlineData("900115 300013")]
    public void Spaces_hyphens_and_non_breaking_spaces_typed_between_digits_are_dropped(string typed)
    {
        var check = IdentityClaimRules.CheckIin(typed);
        Assert.True(check.Ok);
        Assert.Equal("900115300013", check.Value);
    }

    [Theory]
    [InlineData("")]
    [InlineData("12345")]
    [InlineData("9001153000130")]
    [InlineData("90011530001a")]
    public void A_wrong_length_or_a_letter_says_to_type_twelve_digits(string typed)
    {
        var check = IdentityClaimRules.CheckIin(typed);
        Assert.False(check.Ok);
        Assert.Equal(IdentityClaimRules.IinDigitsMessage, check.Error);
    }

    [Theory]
    [InlineData("900115300010")]
    [InlineData("000000000000")]
    // Both sets of weights give 10: no valid control digit exists.
    [InlineData("123456000480")]
    [InlineData("123456000489")]
    public void Twelve_digits_with_a_wrong_control_digit_are_refused(string typed)
    {
        var check = IdentityClaimRules.CheckIin(typed);
        Assert.False(check.Ok);
        Assert.Equal(IdentityClaimRules.IinControlMessage, check.Error);
        Assert.False(IdentityClaimRules.IsValidIin(typed));
    }

    [Fact]
    public void Digits_of_other_scripts_and_a_missing_value_are_refused()
    {
        // Fullwidth and Arabic-Indic digits: char.IsDigit would accept them, the rule does not.
        Assert.False(IdentityClaimRules.IsValidIin("１２３４５６０１２３４４"));
        Assert.False(IdentityClaimRules.IsValidIin("١٢٣٤٥٦٠١٢٣٤٤"));
        Assert.False(IdentityClaimRules.CheckIin("１２３４５６０１２３４４").Ok);
        Assert.False(IdentityClaimRules.CheckIin(null).Ok);
        Assert.False(IdentityClaimRules.IsValidIin(null));
    }

    [Theory]
    [InlineData("Ахметова Айгерим Нурлановна")]
    [InlineData("Нұрсая Әбдіқалық Серікқызы")]
    [InlineData("Ким Алексей")]
    [InlineData("Мухамедьярова-Ли Мадина Бекзатовна")]
    [InlineData("Д'Артаньян Шарль")]
    [InlineData("Aigerim Nurlanovna")]
    public void A_full_name_of_two_or_more_words_passes(string name)
    {
        var check = IdentityClaimRules.CheckFullName(name);
        Assert.True(check.Ok);
        Assert.Equal(name, check.Value);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("Айгерим")]
    [InlineData("Бузурбаева")]
    public void A_single_word_or_nothing_asks_for_surname_and_name(string name)
    {
        var check = IdentityClaimRules.CheckFullName(name);
        Assert.False(check.Ok);
        Assert.Equal(IdentityClaimRules.NameWordsMessage, check.Error);
    }

    [Theory]
    [InlineData("А. Б.")]
    [InlineData("Иванов 2 Иван")]
    [InlineData("Иванов Иван 87771234567")]
    [InlineData("Иванов Иван!!!")]
    [InlineData("а б")]
    public void Initials_digits_and_symbols_are_not_a_full_name(string name)
    {
        var check = IdentityClaimRules.CheckFullName(name);
        Assert.False(check.Ok);
        Assert.Equal(IdentityClaimRules.NameLettersMessage, check.Error);
    }

    [Fact]
    public void Seven_words_and_a_very_long_name_are_refused()
    {
        Assert.False(IdentityClaimRules.CheckFullName("Аа Бб Вв Гг Дд Ее Жж").Ok);
        var tooLong = IdentityClaimRules.CheckFullName("Иванов " + new string('а', 260));
        Assert.False(tooLong.Ok);
        Assert.Equal(IdentityClaimRules.NameLongMessage, tooLong.Error);
        Assert.False(IdentityClaimRules.CheckFullName(null).Ok);
    }

    [Fact]
    public void Spaces_tabs_non_breaking_spaces_and_control_characters_collapse_to_single_spaces()
    {
        Assert.Equal("Иванов Иван Иванович", IdentityClaimRules.NormalizeName("  Иванов \tИван \n Иванович  "));
        Assert.Equal("Иванов Иван", IdentityClaimRules.NormalizeName("Иванов​ Иван"));
        Assert.Equal("", IdentityClaimRules.NormalizeName(null));
        var check = IdentityClaimRules.CheckFullName("  Иванов   Иван ");
        Assert.True(check.Ok);
        Assert.Equal("Иванов Иван", check.Value);
        Assert.Equal(3, IdentityClaimRules.WordCount("Иванов Иван Иванович"));
        Assert.Equal(0, IdentityClaimRules.WordCount("   "));
    }
}
