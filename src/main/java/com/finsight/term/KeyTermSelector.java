package com.finsight.term;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic reading glossary; does not persist terms or call a model. */
public final class KeyTermSelector {
    private KeyTermSelector() {}
    private static final Set<String> EVERYDAY_WORDS = Set.of("주식","시장","기업","가격","상승","하락","투자","회사","정부","경제",
            "AI","반도체","수출","수입","원화","물가","매출","실적");
    private static final Set<String> GENERIC_METADATA = Set.of("주식","시장","기업","가격","상승","하락","투자","회사","정부","경제");
    private static final Set<String> BROAD_TOPICS = Set.of("AI","반도체","수출","수입","원화","물가","매출","실적","코스피","코스닥");
    private static final String PARTICLES = "은|는|이|가|을|를|의|와|과|에|로|도|만|으로|에서|에게|까지|부터|보다|처럼|에도|에는|에선|으로는|에서는|이며|이고|이다|입니다";
    private static final com.github.benmanes.caffeine.cache.Cache<String,Pattern> TERM_PATTERNS =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(512).build();
    private record Entry(String term, int grade, Pattern pattern) {}
    private static Pattern pattern(String term) {
        return TERM_PATTERNS.get(term, value -> {
            String literal = Arrays.stream(value.split(" ")).map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s*"));
            return Pattern.compile("(?<![가-힣A-Za-z0-9])("+literal+")(?=$|[^가-힣A-Za-z0-9]|(?:"+PARTICLES+")(?=$|[^가-힣A-Za-z0-9]))", Pattern.CASE_INSENSITIVE);
        });
    }
    private static Entry entry(String term, int grade) { return new Entry(term, grade, pattern(term)); }
    private static final List<Entry> DICTIONARY = List.of(
            entry("기준금리",1), entry("금리",1), entry("환율",1), entry("영업이익",1), entry("연체율",1), entry("시가총액",1),
            entry("인플레이션",2), entry("변동성",2), entry("수급",2), entry("배당수익률",2), entry("공시",2), entry("유상증자",2),
            entry("공모주",2), entry("수요예측",2), entry("PER",2), entry("PBR",2), entry("공매도",2), entry("유동성",2), entry("밸류업",2),
            entry("실적 가이던스",2), entry("영업이익률",2), entry("국채 금리",2), entry("양적완화",2), entry("밸류에이션",2),
            entry("설비투자",2), entry("재고 조정",2), entry("양적긴축",3), entry("프로젝트파이낸싱",3), entry("PF",3),
            entry("신용 스프레드",3), entry("EPS",3), entry("듀레이션",3), entry("영업레버리지",3)
    ).stream().sorted(Comparator.comparingInt((Entry value)->value.term().length()).reversed()).toList();

    public static boolean containsTerm(String text, String term) {
        return text != null && matchingTerms(text).test(term);
    }

    public static java.util.function.Predicate<String> matchingTerms(String text) {
        String searchable = compact(text);
        return term -> term != null && searchable.contains(compact(term)) && pattern(term).matcher(text).find();
    }

    private static String compact(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if (" \t\n\r\f\u000B".indexOf(ch)>=0) continue;
            result.append(ch>='A' && ch<='Z' ? (char)(ch+('a'-'A')) : ch);
        }
        return result.toString();
    }

    public static boolean isRecommendationTerm(String term) {
        return term != null && !term.isBlank() && !GENERIC_METADATA.contains(term.strip());
    }

    public static double recommendationWeight(String term) { return BROAD_TOPICS.contains(term.toUpperCase(Locale.ROOT).strip()) ? 0.25 : 1.0; }

    public static List<String> canonical(String title, String body, List<Term> dictionary) {
        Map<String,Entry> entries=new LinkedHashMap<>();
        DICTIONARY.forEach(item -> entries.put(item.term(),item));
        dictionary.stream().filter(term -> isRecommendationTerm(term.getTerm()))
                .forEach(term -> entries.putIfAbsent(term.getTerm(),entry(term.getTerm(),term.getKind()==Term.Kind.CONCEPT?2:1)));
        return select(title,body,"analyst",entries.values().stream().sorted(Comparator.comparingInt((Entry e)->e.term().length()).reversed()).toList(),GENERIC_METADATA);
    }

    public static List<String> select(String title, String body, String level) {
        return select(title,body,level,DICTIONARY,EVERYDAY_WORDS);
    }
    private static List<String> select(String title, String body, String level, List<Entry> dictionary, Set<String> excluded) {
        int maximum = switch (level) { case "beginner" -> 1; case "analyst" -> 3; default -> 2; };
        String text = Objects.toString(title, "") + "\n" + Objects.toString(body, "");
        boolean[] occupied = new boolean[text.length()];
        record Candidate(String display, int score, int first) {}
        List<Candidate> candidates = new ArrayList<>();
        for (Entry entry : dictionary) {
            if (entry.grade()>maximum || excluded.contains(entry.term())) continue;
            var matches=entry.pattern().matcher(text); int count=0,first=-1; String display=null;
            while(matches.find()) {
                boolean overlap=false;
                for(int i=matches.start();i<matches.end();i++) overlap |= occupied[i];
                if(overlap) continue;
                for(int i=matches.start();i<matches.end();i++) occupied[i]=true;
                if(first<0) { first=matches.start(); display=matches.group(1); }
                count++;
            }
            if(count>0) candidates.add(new Candidate(display,entry.grade()*4+Math.min(count,5)*2+(first<Objects.toString(title,"").length()?3:0),first));
        }
        return candidates.stream().sorted(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(Candidate::first))
                .limit(6).map(Candidate::display).toList();
    }

    public static Map<String,List<String>> byLevel(String title, String body) {
        return Map.of("beginner",select(title,body,"beginner"),"normal",select(title,body,"normal"),"analyst",select(title,body,"analyst"));
    }

    public static Optional<String> definition(String term) {
        return Optional.ofNullable(switch (term.replaceAll("\\s+","").toUpperCase(Locale.ROOT)) {
            case "공매도" -> "빌린 주식을 먼저 팔고 나중에 사서 갚는 거래 방식입니다. 주가가 오르면 손실이 커질 수 있습니다.";
            case "유동성" -> "자산을 큰 가격 변동 없이 현금화할 수 있는 정도 또는 시장의 자금 공급 여건을 뜻합니다.";
            case "밸류업" -> "기업 가치를 높이기 위한 수익성·자본 효율·주주 환원 개선 노력을 뜻합니다.";
            case "실적가이던스" -> "기업이 제시하는 향후 매출이나 이익 등의 전망·목표입니다. 실제 결과와 다를 수 있습니다.";
            case "영업이익률" -> "매출에서 영업이익이 차지하는 비율입니다. 영업이익을 매출로 나누어 백분율로 표시합니다.";
            case "국채금리" -> "정부가 발행한 채권의 수익률입니다. 채권 가격과 수익률은 반대로 움직입니다.";
            case "양적긴축" -> "중앙은행이 보유 자산을 줄여 시중 유동성을 축소하는 정책입니다.";
            case "신용스프레드" -> "신용 위험이 다른 채권 사이의 금리 차이로, 추가 위험에 대한 보상 수준을 나타냅니다.";
            case "EPS" -> "주당순이익으로, 기업의 순이익을 주식 수로 나눈 지표입니다.";
            case "듀레이션" -> "채권 현금 흐름의 가중 평균 회수 기간으로, 금리 변화에 따른 가격 민감도와 관련됩니다.";
            case "영업레버리지" -> "고정비 때문에 매출 변화보다 영업이익이 더 크게 변할 수 있는 효과입니다.";
            case "재고조정" -> "판매 상황에 맞춰 재고와 생산량을 조절하는 과정입니다.";
            default -> null;
        });
    }
}
