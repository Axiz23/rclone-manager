//! rclone-style glob used by the backup include / exclude filters:
//! `*` (no slash), `**`, `?`, `{a,b}` and `[abc]`. A pattern that does not start with `/` matches at any depth.

use regex::Regex;

pub struct Glob {
    /// None when the pattern is invalid; such a pattern never matches.
    re: Option<Regex>,
}

impl Glob {
    pub fn new(pattern: &str) -> Glob {
        Glob { re: build(pattern) }
    }

    /// `rel` is the path relative to the backup source, with `/` separators.
    pub fn matches(&self, rel: &str) -> bool {
        self.re.as_ref().map(|r| r.is_match(rel)).unwrap_or(false)
    }
}

fn build(pattern: &str) -> Option<Regex> {
    let anchored = pattern.starts_with('/');
    let p: Vec<char> = pattern.trim_start_matches('/').chars().collect();
    let mut body = String::new();
    let mut depth = 0usize;
    let mut i = 0usize;
    while i < p.len() {
        let c = p[i];
        if c == '*' && i + 1 < p.len() && p[i + 1] == '*' {
            body.push_str(".*");
            i += 1;
        } else if c == '*' {
            body.push_str("[^/]*");
        } else if c == '?' {
            body.push_str("[^/]");
        } else if c == '{' {
            depth += 1;
            body.push_str("(?:");
        } else if c == '}' && depth > 0 {
            depth -= 1;
            body.push(')');
        } else if c == ',' && depth > 0 {
            body.push('|');
        } else if c == '[' && p[i + 1..].contains(&']') {
            // Copy the character class through its first closing bracket unchanged.
            let end = i + 1 + p[i + 1..].iter().position(|&x| x == ']').unwrap_or(0);
            body.extend(p[i..=end].iter());
            i = end;
        } else {
            body.push_str(&regex::escape(&c.to_string()));
        }
        i += 1;
    }
    let prefix = if anchored { "" } else { "(?:.*/)?" };
    Regex::new(&format!(r"\A{prefix}(?:{body})\z")).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn star_does_not_cross_directories() {
        let g = Glob::new("*.tmp");
        assert!(g.matches("a.tmp"));
        assert!(g.matches("dir/a.tmp")); // unanchored: any depth
        assert!(!g.matches("a.txt"));
    }

    #[test]
    fn anchored_patterns() {
        let g = Glob::new("/*.tmp");
        assert!(g.matches("a.tmp"));
        assert!(!g.matches("dir/a.tmp"));
    }

    #[test]
    fn double_star() {
        let g = Glob::new("cache/**");
        assert!(g.matches("cache/a/b.bin"));
        assert!(g.matches("x/cache/a"));
        assert!(!g.matches("cachex/a"));
    }

    #[test]
    fn alternatives_and_classes() {
        let g = Glob::new("*.{jpg,png}");
        assert!(g.matches("a.jpg"));
        assert!(g.matches("a.png"));
        assert!(!g.matches("a.gif"));
        let c = Glob::new("img[12].jpg");
        assert!(c.matches("img1.jpg"));
        assert!(!c.matches("img3.jpg"));
        assert!(Glob::new("a?c").matches("abc"));
    }

    #[test]
    fn invalid_patterns_never_match() {
        assert!(!Glob::new("{unclosed").matches("{unclosed"));
    }
}
