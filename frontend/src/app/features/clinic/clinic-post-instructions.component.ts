import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

export interface PostInstructionCategory {
  id: string;
  titleEn: string;
  titleAr: string;
  icon: string;
  badge: string;
  immediateCare: { en: string[]; ar: string[] };
  dos: { en: string[]; ar: string[] };
  donts: { en: string[]; ar: string[] };
  medications: { en: string; ar: string };
  warningSigns: { en: string[]; ar: string[] };
}

const INSTRUCTION_CATEGORIES: PostInstructionCategory[] = [
  {
    id: 'extraction',
    titleEn: 'Tooth Extraction & Oral Surgery',
    titleAr: 'خلع الأسنان وجراحة الفم',
    icon: 'tooth',
    badge: 'SURGICAL',
    immediateCare: {
      en: [
        'Keep firm, continuous biting pressure on the sterile gauze pack for 45 to 60 minutes.',
        'If bleeding persists after removing gauze, place a fresh folded gauze or damp black tea bag and bite firmly for 30 more minutes.',
        'Apply an ice pack to the cheek outside the surgical area for 15 minutes ON / 15 minutes OFF for the first 24 hours to reduce swelling.',
      ],
      ar: [
        'العض بشكل مستمر وثابت على الشاش المعقم لمدة 45 إلى 60 دقيقة دون تحريكه.',
        'إذا استمر النزف بعد إزالة الشاش، ضع قطعة شاش جديدة أو كيس شاي أسود رطب وعض عليه لمدة 30 دقيقة إضافية.',
        'وضع كمادات باردة/ثلج على الخد من الخارج (15 دقيقة وضع / 15 دقيقة راحة) خلال أول 24 ساعة لتقليل التورم.',
      ],
    },
    dos: {
      en: [
        'Rest with your head slightly elevated on pillows for the first 24 hours.',
        'Stick to a soft, cool diet (yogurt, smoothies, lukewarm soup, mashed potatoes).',
        'Begin gentle warm salt-water rinses (1/2 tsp salt in 1 cup warm water) AFTER 24 hours, 3–4 times daily after meals.',
        'Brush your remaining teeth gently, carefully avoiding the extraction socket.',
      ],
      ar: [
        'الراحة التامة مع إبقاء الرأس مرتفعاً قليلاً على وسادة خلال أول 24 ساعة.',
        'تناول الأطعمة اللينة والباردة أو الفاترة (لبن زبادي، عصائر، شوربة دافئة، بطاطا مهروسة).',
        'البدء بالمضمضة بلطف بالماء الدافئ والملح (نصف ملعقة صغيرة ملح في كوب ماء) بعد مرور 24 ساعة، 3-4 مرات يومياً بعد الوجبات.',
        'تنظيف بقية الأسنان بالفرشاة بحذر شديد مع تجنب لمس مكان الخلع.',
      ],
    },
    donts: {
      en: [
        'DO NOT spit forcefully, suck through a straw, or rinse vigorously for 24 hours (this dislodges the blood clot and causes painful Dry Socket).',
        'DO NOT smoke or vape for at least 48 to 72 hours; nicotine severely impairs healing.',
        'DO NOT consume hot liquids, carbonated drinks, alcohol, or spicy/crunchy foods.',
        'DO NOT touch or probe the socket with your fingers, tongue, or toothpicks.',
      ],
      ar: [
        'ممنوع البصق بقوة، الشفط بالقشة (المصاصة)، أو المضمضة العنيفة خلال أول 24 ساعة (تجنباً لانفصال الخثرة الدموية وحدوث السنخ الجاف المؤلم).',
        'ممنوع التدخين أو السجائر الإلكترونية لمدة 48 إلى 72 ساعة على الأقل؛ النيكوتين يؤخر التئام الجرح بشكل خطير.',
        'ممنوع المشروبات الساخنة جداً، الغازية، الحارة، أو الأطعمة الصلبة والمقرمشة.',
        'ممنوع لمس مكان الجرح بالأصابع، اللسان، أو العيدان الخشبية.',
      ],
    },
    medications: {
      en: 'Take prescribed analgesics (Ibuprofen / Paracetamol) before the local numbness completely wears off. Complete any prescribed antibiotic course entirely.',
      ar: 'تناول المسكنات الموصوفة (مثل الإيبوبروفين أو الباراسيتامول) قبل زوال مفعول التخدير الموضعي. يجب إكمال كورس المضاد الحيوي كاملاً في حال تم وصفه.',
    },
    warningSigns: {
      en: [
        'Severe, persistent throbbing pain not relieved by medication after 48 hours (potential Dry Socket).',
        'Active, heavy bleeding that does not stop with biting on gauze.',
        'Fever exceeding 38.5°C (101°F) or increasing difficulty swallowing/opening mouth.',
      ],
      ar: [
        'ألم شديد ونابض لا يهدأ بالمسكنات بعد مرور 48 ساعة (اشتباه سنخ جاف).',
        'نزيف دموي نشط وغزير لا يتوقف رغم العض المستمر على الشاش.',
        'ارتفاع في درجة الحرارة فوق 38.5°C أو صعوبة في البلع وفتح الفم.',
      ],
    },
  },
  {
    id: 'root-canal',
    titleEn: 'Root Canal Treatment (Endodontics)',
    titleAr: 'علاج العصب وجذور الأسنان',
    icon: 'activity',
    badge: 'ENDODONTIC',
    immediateCare: {
      en: [
        'Avoid chewing or biting on the treated side until local anesthesia has completely worn off to avoid soft tissue injury.',
        'Avoid hard, sticky, or chewy foods until the permanent crown or restoration is placed.',
        'Mild tenderness when biting or tapping the tooth is completely normal for 2 to 4 days as the surrounding ligament heals.',
      ],
      ar: [
        'تجنب المضغ أو العض على جهة السن المعالج حتى يزول التخدير تماماً لتفادي عض الخد أو اللسان.',
        'تجنب الأطعمة الصلبة أو اللزجة حتى يتم تركيب الحشوة الدائمة أو التاج النهائي.',
        'من الطبيعي جداً الشعور بحساسية أو ألم خفيف عند الضغط على السن لمدة 2 إلى 4 أيام ريثما يتعافى الرباط المحيط بالجذر.',
      ],
    },
    dos: {
      en: [
        'Continue routine gentle brushing and flossing around the tooth.',
        'Schedule your permanent core restoration or crown promptly to protect the tooth from fracture.',
        'Take prescribed anti-inflammatory pain medication at scheduled intervals.',
      ],
      ar: [
        'الاستمرار في تنظيف الأسنان بالفرشاة والخيط بلطف كالمعتاد.',
        'المبادرة بحجز موعد التلبيس (التاج) أو الحشوة الدائمة النهائية لحماية السن من الكسر.',
        'تناول مضادات الالتهاب والمسكنات الموصوفة في مواعيدها بانتظام.',
      ],
    },
    donts: {
      en: [
        'DO NOT chew hard candy, ice cubes, nuts, or crusty bread on the temporary restoration.',
        'DO NOT pick at the temporary filling material; a slight indentation or medicine taste is normal.',
      ],
      ar: [
        'ممنوع مضغ الأطعمة القاسية مثل المكسرات والثلج والخبز المقرمش على الحشوة المؤقتة.',
        'ممنوع العبث بمادة الحشوة المؤقتة بأي أداة حادة؛ الشعور بطعم دوائي طفيف أمر طبيعي.',
      ],
    },
    medications: {
      en: 'Ibuprofen 400-600mg every 6-8 hours with food is recommended for post-instrumentation inflammation.',
      ar: 'يوصى بمسكن مضاد للالتهاب (مثل إيبوبروفين 400-600 ملغ) كل 6-8 ساعات بعد الأكل لتخفيف التهاب ذروة الجذر.',
    },
    warningSigns: {
      en: [
        'Visible facial or gum swelling developing near the treated tooth.',
        'Complete dislodgement of the temporary filling.',
        'Severe throbbing pain that keeps you awake at night.',
      ],
      ar: [
        'ظهور تورم أو انتفاخ واضح في اللثة أو الوجه بالقرب من السن المعالج.',
        'سقوط الحشوة المؤقتة بالكامل وانكشاف قنوات السن.',
        'ألم شديد ومستمر يمنعك من النوم.',
      ],
    },
  },
  {
    id: 'implants',
    titleEn: 'Dental Implants & Bone Grafting',
    titleAr: 'زراعة الأسنان وتطعيم العظام',
    icon: 'shield',
    badge: 'IMPLANTOLOGY',
    immediateCare: {
      en: [
        'Bite on the gauze pad for 1 hour. Minor oozing of pink saliva is normal for the first 24 hours.',
        'Keep ice packs on the jaw intermittently for 24-48 hours to minimize facial swelling and bruising.',
        'Do not rinse or disturb the surgical sutures for 24 hours.',
      ],
      ar: [
        'العض على الشاش لمدة ساعة كاملة. نزول لعاب مائل للوردي أمر طبيعي خلال أول 24 ساعة.',
        'وضع كمادات باردة على الفك بشكل متقطع لمدة 24-48 ساعة لتخفيف التورم والكدمات.',
        'عدم المضمضة أو لمس خيوط الجراحة خلال أول 24 ساعة.',
      ],
    },
    dos: {
      en: [
        'Maintain a strict soft/liquid diet for the first week (protein shakes, lukewarm purees).',
        'Use the prescribed Chlorhexidine 0.12% antibacterial mouthwash twice daily starting day 2; gently roll it over the site without swishing vigorously.',
        'Take all antibiotics to completion to prevent graft contamination.',
      ],
      ar: [
        'الالتزام بنظام غذائي لين تماماً خلال الأسبوع الأول (سوائل مغذية، أطعمة مهروسة فاترة).',
        'استخدام غسول الفم الموصوف (كلورهيكسيدين 0.12%) مرتين يومياً بدءاً من اليوم الثاني برفق دون مضمضة شديدة.',
        'إكمال المضاد الحيوي كاملاً حتى آخر جرعة لمنع تلوث الطعم العظمي.',
      ],
    },
    donts: {
      en: [
        'DO NOT pull or stretch your lip/cheek to inspect the implant or stitches.',
        'DO NOT wear any removable dentures or retainers over the surgical site unless adjusted by your dentist.',
        'DO NOT smoke for at least 2 weeks; smoking increases implant failure rate by over 400%.',
      ],
      ar: [
        'ممنوع شد الشفة أو الخد للنظر إلى موقع الزراعة أو الخيوط الجراحية.',
        'ممنوع ارتداء أي أطقم أسنان متحركة أو مثبتات تضغط على موقع الزرعة إلا بعد تعديل الطبيب لها.',
        'ممنوع التدخين لمدة أسبوعين على الأقل؛ التدخين يرفع نسبة فشل الزراعة بأكثر من 4 أضعاف.',
      ],
    },
    medications: {
      en: 'Antibiotic therapy + Analgesic coverage as prescribed. Chlorhexidine mouthwash 15ml for 30 seconds twice daily.',
      ar: 'المضاد الحيوي الموصوف بالكامل + المسكنات. غسول الكلورهيكسيدين (15 مل) لمدة 30 ثانية مرتين يومياً بعد تنظيف الأسنان.',
    },
    warningSigns: {
      en: [
        'Persistent numbness of the lip, chin, or tongue lasting more than 8 hours post-op.',
        'Feeling the implant or healing abutment is loose or mobile.',
        'Purulent discharge (pus) or foul taste from the implant site.',
      ],
      ar: [
        'استمرار خدران أو تنميل الشفة أو الذقن أو اللسان لأكثر من 8 ساعات بعد انتهاء الجراحة.',
        'الشعور بحركة أو تخلخل في الزرعة أو برغي الشفاء.',
        'خروج إفرازات صديدية أو طعم سيء من مكان الزرعة.',
      ],
    },
  },
  {
    id: 'whitening',
    titleEn: 'Teeth Whitening & Aesthetic Veneers',
    titleAr: 'تبييض وتجميل الأسنان',
    icon: 'sun',
    badge: 'COSMETIC',
    immediateCare: {
      en: [
        'Follow the "White Diet" strictly for the next 48 hours while enamel pores are open and receptive to pigments.',
        'Mild transient sensitivity to cold or air is normal and typically resolves within 24 to 48 hours.',
      ],
      ar: [
        'الالتزام الصارم بـ "الحمية البيضاء" (White Diet) لمدة 48 ساعة حيث تكون مسام الميناء مفتوحة وسريعة الامتصاص للتصبغات.',
        'ظهور حساسية طفيفة ومؤقتة تجاه البرودة أو الهواء أمر طبيعي ويزول خلال 24 إلى 48 ساعة.',
      ],
    },
    dos: {
      en: [
        'Eat clear/white foods: milk, water, white rice, plain pasta, chicken breast, white fish, bananas.',
        'Use desensitizing fluoride toothpaste (Sensodyne/Relief ACP) if sensitivity occurs.',
        'Rinse with plain water immediately after meals.',
      ],
      ar: [
        'تناول الأطعمة البيضاء والفاتحة: حليب، ماء، أرز أبيض، معكرونة بصلصة بيضاء، دجاج، سمك أبيض، موز.',
        'استخدام معجون أسنان مخصص للأسنان الحساسة غني بالفلورايد في حال وجود حساسية.',
        'المضمضة بالماء النقي فور الانتهاء من أي وجبة طعام.',
      ],
    },
    donts: {
      en: [
        'AVOID staining substances for 48 hours: Coffee, black tea, cola, red wine, berries, curry, soy sauce, mustard, ketchup, tobacco.',
        'DO NOT use colored or tinted mouthwashes (blue, green, red chlorhexidine).',
        'DO NOT consume extremely icy or steaming hot drinks.',
      ],
      ar: [
        'تجنب الأطعمة والمشروبات الملونة لمدة 48 ساعة: القهوة، الشاي، الكولا، التوت، الكاري، صوص الصويا، الكاتشب، والتدخين.',
        'ممنوع استخدام غسولات الفم الملونة (الزرقاء أو الحمراء أو الخضراء).',
        'ممنوع المشروبات المثلجة جداً أو شديدة السخونة.',
      ],
    },
    medications: {
      en: 'Potassium nitrate desensitizing gel or mild Paracetamol if teeth feel sensitive.',
      ar: 'جل نترات البوتاسيوم المزيل للحساسية أو باراسيتامول خفيف عند اللزوم.',
    },
    warningSigns: {
      en: [
        'Severe burning sensation on the gums (chemical gel burn).',
        'Severe sharp pain that does not subside after 48 hours.',
      ],
      ar: [
        'شعور بحرقة شديدة في اللثة أو ظهور بقع بيضاء مؤلمة على اللثة.',
        'ألم حاد ومستمر لا يتحسن بعد مرور 48 ساعة.',
      ],
    },
  },
  {
    id: 'restorations',
    titleEn: 'Fillings & Cosmetic Composite Restorations',
    titleAr: 'الحشوات التجميلية والترميمية',
    icon: 'feather',
    badge: 'RESTORATIVE',
    immediateCare: {
      en: [
        'Composite (white) fillings are fully cured and hardened instantly with UV light, so you can eat as soon as local anesthesia wears off.',
        'Do not chew on numb lips, tongue, or cheeks to avoid severe accidental soft tissue bites.',
      ],
      ar: [
        'الحشوات الضوئية التجميلية تتصلب فورياً بضوء المعالجة، لذا يمكنك الأكل بمجرد زوال التخدير الموضعي.',
        'تجنب المضغ أثناء استمرار التخدير لتفادي عض الشفة أو الخد أو اللسان بالخطأ.',
      ],
    },
    dos: {
      en: [
        'Brush and floss normally around the new filling.',
        'Notice your bite when eating; both sides should feel even and comfortable.',
        'Call the clinic if your bite feels higher than normal on that tooth.',
      ],
      ar: [
        'تنظيف الأسنان بالفرشاة والخيط كالمعتاد حول الحشوة الجديدة.',
        'ملاحظة إطباق الأسنان عند الأكل؛ يجب أن يكون الإطباق متوازناً ومريحاً على الطرفين.',
        'التواصل مع العيادة لإجراء تنعيم وتعديل بسيط إذا شعرت أن الحشوة مرتفعة قليلاً عند الإطباق.',
      ],
    },
    donts: {
      en: [
        'DO NOT attempt to crack nutshells, open bottle caps, or bite hard objects with restored teeth.',
        'DO NOT ignore a "high bite" (this causes traumatic ligament inflammation).',
      ],
      ar: [
        'ممنوع كسر المكسرات القاسية أو فتح الأغطية بالأسنان المعالجة.',
        'ممنوع إهمال الحشوة المرتفعة؛ لأن الضغط الزائد يسبب التهاباً مؤلماً في عصب السن.',
      ],
    },
    medications: {
      en: 'Mild analgesic if the injection site or tooth gum margin is tender.',
      ar: 'مسكن بسيط عند الحاجة في حال وجود حساسية مكان إبرة التخدير أو حافة اللثة.',
    },
    warningSigns: {
      en: [
        'Feeling premature contact or high point whenever you close your teeth together.',
        'Sharp pain upon chewing hard foods.',
      ],
      ar: [
        'الشعور بأن السن يلامس قبل باقي الأسنان عند إغلاق الفك (عضة مرتفعة).',
        'ألم حاد وخاطف عند الضغط على طعام قاسي.',
      ],
    },
  },
];

@Component({
  selector: 'fe-clinic-post-instructions',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="tl-page" data-testid="clinic-post-instructions">
      <!-- Breadcrumbs -->
      <div class="tl-breadcrumbs no-print">
        <a routerLink="/app/dashboard">Home</a>
        <span class="tl-bc-sep">»</span>
        <span>Clinic</span>
        <span class="tl-bc-sep">»</span>
        <span class="active">{{ language() === 'ar' ? 'تعليمات ما بعد المعالجة' : 'Post-Operative Instructions' }}</span>
      </div>

      <!-- Main ToothLogic Card -->
      <div class="tl-card no-print">
        <!-- Top Toolbar with Category Pills and Actions -->
        <div class="tl-toolbar">
          <div class="tl-toolbar-left pi-tabs-wrap">
            @for (cat of categories; track cat.id) {
              <button
                type="button"
                class="pi-tab-pill"
                [class.is-active]="selectedCategoryId() === cat.id"
                (click)="selectedCategoryId.set(cat.id)"
              >
                <span class="pi-pill-badge">{{ cat.badge }}</span>
                <span class="pi-pill-title">{{ language() === 'ar' ? cat.titleAr : cat.titleEn }}</span>
              </button>
            }
          </div>

          <div class="tl-toolbar-right">
            <!-- Language Toggle Button -->
            <button
              type="button"
              class="tl-btn tl-btn-outline"
              (click)="toggleLanguage()"
              [title]="language() === 'ar' ? 'English View' : 'العرض باللغة العربية'"
            >
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="2" y1="12" x2="22" y2="12"></line>
                <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
              </svg>
              <span>{{ language() === 'ar' ? 'English' : 'عربي' }}</span>
            </button>

            <!-- Customize Details Side Drawer Trigger -->
            <button
              type="button"
              class="tl-btn tl-btn-outline"
              (click)="isDrawerOpen.set(true)"
              [title]="language() === 'ar' ? 'تخصيص بيانات المريض والطبيب' : 'Customize Patient & Doctor Info'"
            >
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
              </svg>
              <span>{{ language() === 'ar' ? 'تخصيص البيانات' : 'Customize' }}</span>
            </button>

            <!-- Copy to WhatsApp / Clipboard -->
            <button
              type="button"
              class="tl-btn tl-btn-outline"
              (click)="copyToClipboard()"
              [class.is-copied]="copied()"
              [title]="language() === 'ar' ? 'نسخ الإرشادات لمشاركتها بالواتساب' : 'Copy instructions to share via WhatsApp'"
            >
              @if (copied()) {
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <polyline points="20 6 9 17 4 12"></polyline>
                </svg>
                <span>{{ language() === 'ar' ? 'تم النسخ!' : 'Copied!' }}</span>
              } @else {
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                  <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                </svg>
                <span>{{ language() === 'ar' ? 'نسخ للواتساب' : 'Copy' }}</span>
              }
            </button>

            <!-- Print Care Sheet -->
            <button type="button" class="tl-btn tl-btn-teal" (click)="triggerPrint()">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <polyline points="6 9 6 2 18 2 18 9"></polyline>
                <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"></path>
                <rect x="6" y="14" width="12" height="8"></rect>
              </svg>
              <span>{{ language() === 'ar' ? 'طباعة التعليمات' : 'Print Care Sheet' }}</span>
            </button>
          </div>
        </div>

        <!-- Sleek 1-Row Clinical Metadata Strip -->
        <div class="pi-meta-strip">
          <div class="pi-strip-title">
            <span class="pi-strip-badge">{{ selectedCategory().badge }}</span>
            <strong class="pi-strip-heading">{{ language() === 'ar' ? selectedCategory().titleAr : selectedCategory().titleEn }}</strong>
          </div>

          <div class="pi-strip-tags">
            <div class="pi-tag">
              <span class="pi-tag-label">{{ language() === 'ar' ? 'المريض:' : 'Patient:' }}</span>
              <strong class="pi-tag-val">{{ patientName() || '-' }}</strong>
            </div>
            <div class="pi-tag-sep"></div>
            <div class="pi-tag">
              <span class="pi-tag-label">{{ language() === 'ar' ? 'الطبيب المعالج:' : 'Dentist:' }}</span>
              <strong class="pi-tag-val">{{ doctorName() || '-' }}</strong>
            </div>
            <div class="pi-tag-sep"></div>
            <div class="pi-tag">
              <span class="pi-tag-label">{{ language() === 'ar' ? 'التاريخ:' : 'Date:' }}</span>
              <span class="pi-tag-val">{{ instructionDate() }}</span>
            </div>
            <div class="pi-tag-sep"></div>
            <div class="pi-tag">
              <span class="pi-tag-label">{{ language() === 'ar' ? 'طوارئ العيادة:' : 'Helpline:' }}</span>
              <span class="pi-tag-val pi-tag-val--phone">{{ emergencyPhone() }}</span>
            </div>
            <button type="button" class="pi-tag-edit" (click)="isDrawerOpen.set(true)" [title]="language() === 'ar' ? 'تعديل البيانات' : 'Edit info'">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 20h9"></path>
                <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"></path>
              </svg>
            </button>
          </div>
        </div>

        <!-- 2-Column Content Layout: Fits without excessive scrolling -->
        <div class="pi-grid-container" [attr.dir]="language() === 'ar' ? 'rtl' : 'ltr'">
          <!-- Column 1: Immediate Care & DOs -->
          <div class="pi-grid-col">
            <!-- Immediate Care Section -->
            <section class="pi-box pi-box--neutral">
              <div class="pi-box-head">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" class="pi-icon-teal" aria-hidden="true">
                  <circle cx="12" cy="12" r="10"></circle>
                  <polyline points="12 6 12 12 16 14"></polyline>
                </svg>
                <h3 class="pi-box-title">{{ language() === 'ar' ? 'تعليمات الساعات الأولى (أول 24 ساعة)' : 'Immediate Care (First 24 Hours)' }}</h3>
              </div>
              <ul class="pi-box-list">
                @for (item of (language() === 'ar' ? selectedCategory().immediateCare.ar : selectedCategory().immediateCare.en); track item) {
                  <li>{{ item }}</li>
                }
              </ul>
            </section>

            <!-- DOs Box (Soft calm clinical green) -->
            <section class="pi-box pi-box--dos">
              <div class="pi-box-head">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" class="pi-icon-ok" aria-hidden="true">
                  <polyline points="20 6 9 17 4 12"></polyline>
                </svg>
                <h3 class="pi-box-title pi-title--ok">{{ language() === 'ar' ? 'إرشادات العناية الموصى بها' : 'Recommended Care Guidelines' }}</h3>
              </div>
              <ul class="pi-box-list">
                @for (item of (language() === 'ar' ? selectedCategory().dos.ar : selectedCategory().dos.en); track item) {
                  <li>{{ item }}</li>
                }
              </ul>
            </section>
          </div>

          <!-- Column 2: DON'Ts, Medications & Warning Signs -->
          <div class="pi-grid-col">
            <!-- DON'Ts Box (Soft calm clinical red) -->
            <section class="pi-box pi-box--donts">
              <div class="pi-box-head">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" class="pi-icon-bad" aria-hidden="true">
                  <line x1="18" y1="6" x2="6" y2="18"></line>
                  <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
                <h3 class="pi-box-title pi-title--bad">{{ language() === 'ar' ? 'تعليمات ضرورية' : 'Essential Precautions' }}</h3>
              </div>
              <ul class="pi-box-list">
                @for (item of (language() === 'ar' ? selectedCategory().donts.ar : selectedCategory().donts.en); track item) {
                  <li>{{ item }}</li>
                }
              </ul>
            </section>

            <!-- Medications Box (Calm ToothLogic accent) -->
            <section class="pi-box pi-box--meds">
              <div class="pi-box-head">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" class="pi-icon-teal" aria-hidden="true">
                  <path d="M10.5 20.5l10-10a4.95 4.95 0 1 0-7-7l-10 10a4.95 4.95 0 1 0 7 7Z"></path>
                  <line x1="8.5" y1="8.5" x2="15.5" y2="15.5"></line>
                </svg>
                <h3 class="pi-box-title">{{ language() === 'ar' ? 'إرشادات الأدوية والمسكنات' : 'Medications & Pain Control' }}</h3>
              </div>
              <p class="pi-med-text">
                {{ language() === 'ar' ? selectedCategory().medications.ar : selectedCategory().medications.en }}
              </p>
            </section>

            <!-- Warning Signs Box (Soft calm amber) -->
            <section class="pi-box pi-box--warnings">
              <div class="pi-box-head">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" class="pi-icon-warn" aria-hidden="true">
                  <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
                  <line x1="12" y1="9" x2="12" y2="13"></line>
                  <line x1="12" y1="17" x2="12.01" y2="17"></line>
                </svg>
                <h3 class="pi-box-title pi-title--warn">{{ language() === 'ar' ? 'علامات تستوجب مراجعة العيادة فوراً' : 'Contact Clinic Immediately If' }}</h3>
              </div>
              <ul class="pi-box-list">
                @for (item of (language() === 'ar' ? selectedCategory().warningSigns.ar : selectedCategory().warningSigns.en); track item) {
                  <li>{{ item }}</li>
                }
              </ul>
            </section>
          </div>
        </div>
      </div>

      <!-- ToothLogic Side Drawer for Customization -->
      @if (isDrawerOpen()) {
        <div class="tl-drawer-backdrop no-print" (click)="isDrawerOpen.set(false)">
          <div class="tl-drawer" (click)="$event.stopPropagation()" [attr.dir]="language() === 'ar' ? 'rtl' : 'ltr'">
            <div class="tl-drawer-head">
              <div>
                <h3>{{ language() === 'ar' ? 'تخصيص ورقة التعليمات' : 'Customize Care Sheet' }}</h3>
                <span class="tl-drawer-sub">{{ language() === 'ar' ? 'تعديل بيانات المريض والطبيب قبل الطباعة أو المشاركة' : 'Edit patient & practitioner details before print or export' }}</span>
              </div>
              <button type="button" class="tl-btn-close" (click)="isDrawerOpen.set(false)" aria-label="Close">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="18" y1="6" x2="6" y2="18"></line>
                  <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
              </button>
            </div>

            <div class="tl-drawer-body">
              <div class="tl-form-stack">
                <label class="tl-form-group">
                  <span class="tl-label">{{ language() === 'ar' ? 'اسم المريض:' : 'Patient Name:' }}</span>
                  <input
                    type="text"
                    class="tl-input"
                    [ngModel]="patientName()"
                    (ngModelChange)="patientName.set($event)"
                    placeholder="Patient Name"
                  />
                </label>

                <label class="tl-form-group">
                  <span class="tl-label">{{ language() === 'ar' ? 'الطبيب المعالج:' : 'Attending Dentist:' }}</span>
                  <input
                    type="text"
                    class="tl-input"
                    [ngModel]="doctorName()"
                    (ngModelChange)="doctorName.set($event)"
                    placeholder="Dr. Name"
                  />
                </label>

                <label class="tl-form-group">
                  <span class="tl-label">{{ language() === 'ar' ? 'هاتف الطوارئ / العيادة:' : 'Emergency Helpline Phone:' }}</span>
                  <input
                    type="text"
                    class="tl-input"
                    [ngModel]="emergencyPhone()"
                    (ngModelChange)="emergencyPhone.set($event)"
                    placeholder="+962 6 500 1234"
                  />
                </label>

                <label class="tl-form-group">
                  <span class="tl-label">{{ language() === 'ar' ? 'تاريخ الإجراء:' : 'Procedure Date:' }}</span>
                  <input
                    type="date"
                    class="tl-input"
                    [ngModel]="instructionDate()"
                    (ngModelChange)="instructionDate.set($event)"
                  />
                </label>

                <label class="tl-form-group">
                  <span class="tl-label">{{ language() === 'ar' ? 'نوع الإجراء:' : 'Procedure Category:' }}</span>
                  <select
                    class="tl-select"
                    [value]="selectedCategoryId()"
                    (change)="selectedCategoryId.set($any($event.target).value)"
                  >
                    @for (cat of categories; track cat.id) {
                      <option [value]="cat.id">{{ language() === 'ar' ? cat.titleAr : cat.titleEn }}</option>
                    }
                  </select>
                </label>
              </div>
            </div>

            <div class="tl-drawer-foot">
              <button type="button" class="tl-btn tl-btn-dark" (click)="isDrawerOpen.set(false)">
                {{ language() === 'ar' ? 'إلغاء' : 'Cancel' }}
              </button>
              <button type="button" class="tl-btn tl-btn-teal" (click)="isDrawerOpen.set(false)">
                {{ language() === 'ar' ? 'تم الحفظ' : 'Done' }}
              </button>
            </div>
          </div>
        </div>
      }

      <!-- Clean Printable Medical Handout (Rendered ONLY in @media print) -->
      <article class="pi-print-sheet" [attr.dir]="language() === 'ar' ? 'rtl' : 'ltr'">
        <header class="pi-print-head">
          <div class="pi-print-brand">
            <h1 class="pi-print-clinic">BrightSmiles Dental Clinic</h1>
            <span class="pi-print-clinic-sub">{{ language() === 'ar' ? 'مركز طب وجراحة الفم والأسنان' : 'Center for Dental Excellence & Oral Surgery' }}</span>
          </div>
          <div class="pi-print-meta">
            <span class="pi-print-badge">{{ language() === 'ar' ? 'إرشادات العناية المنزلية بعد العلاج' : 'POST-OPERATIVE HOME CARE' }}</span>
            <span class="pi-print-date">{{ instructionDate() }}</span>
          </div>
        </header>

        <div class="pi-print-patient-bar">
          <div>
            <span class="pi-print-lbl">{{ language() === 'ar' ? 'اسم المريض:' : 'Patient:' }}</span>
            <strong>{{ patientName() || '-' }}</strong>
          </div>
          <div>
            <span class="pi-print-lbl">{{ language() === 'ar' ? 'الطبيب المعالج:' : 'Attending Dentist:' }}</span>
            <strong>{{ doctorName() || '-' }}</strong>
          </div>
          <div>
            <span class="pi-print-lbl">{{ language() === 'ar' ? 'طوارئ العيادة:' : 'Clinic Helpline:' }}</span>
            <span>{{ emergencyPhone() }}</span>
          </div>
        </div>

        <div class="pi-print-proc-header">
          <h2>{{ language() === 'ar' ? selectedCategory().titleAr : selectedCategory().titleEn }}</h2>
          <span class="pi-print-proc-badge">{{ selectedCategory().badge }}</span>
        </div>

        <section class="pi-print-section">
          <h3>1. {{ language() === 'ar' ? 'تعليمات الساعات الأولى (أول 24 ساعة):' : 'Immediate Care (First 24 Hours):' }}</h3>
          <ul>
            @for (item of (language() === 'ar' ? selectedCategory().immediateCare.ar : selectedCategory().immediateCare.en); track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>

        <div class="pi-print-grid">
          <div class="pi-print-col">
            <h4>{{ language() === 'ar' ? 'إرشادات العناية الموصى بها:' : 'Recommended Care Guidelines:' }}</h4>
            <ul>
              @for (item of (language() === 'ar' ? selectedCategory().dos.ar : selectedCategory().dos.en); track item) {
                <li>{{ item }}</li>
              }
            </ul>
          </div>
          <div class="pi-print-col">
            <h4>{{ language() === 'ar' ? 'تعليمات ضرورية:' : 'Essential Precautions:' }}</h4>
            <ul>
              @for (item of (language() === 'ar' ? selectedCategory().donts.ar : selectedCategory().donts.en); track item) {
                <li>{{ item }}</li>
              }
            </ul>
          </div>
        </div>

        <section class="pi-print-section">
          <h3>2. {{ language() === 'ar' ? 'إرشادات الأدوية والمسكنات:' : 'Medications & Pain Control:' }}</h3>
          <p>{{ language() === 'ar' ? selectedCategory().medications.ar : selectedCategory().medications.en }}</p>
        </section>

        <section class="pi-print-section pi-print-warn-section">
          <h3>3. {{ language() === 'ar' ? 'علامات تستوجب الاتصال بالعيادة فوراً:' : 'Contact Clinic Immediately If:' }}</h3>
          <ul>
            @for (item of (language() === 'ar' ? selectedCategory().warningSigns.ar : selectedCategory().warningSigns.en); track item) {
              <li>{{ item }}</li>
            }
          </ul>
        </section>

        <footer class="pi-print-footer">
          <div class="pi-print-sig">
            <div class="pi-print-sig-line"></div>
            <span>{{ language() === 'ar' ? 'توقيع وختم الطبيب المعالج' : 'Dentist Signature & Stamp' }}</span>
          </div>
          <div class="pi-print-sig">
            <div class="pi-print-sig-line"></div>
            <span>{{ language() === 'ar' ? 'توقيع المريض باستلام التعليمات' : 'Patient Acknowledgement' }}</span>
          </div>
        </footer>
      </article>
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }

      /* Toolbar Pill Tabs */
      .pi-tabs-wrap {
        display: flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
      }
      .pi-tab-pill {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        height: 34px;
        padding: 0 10px;
        border-radius: 6px;
        border: 1px solid var(--line);
        background: var(--surf2);
        color: var(--ink);
        font-size: 0.8rem;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.15s ease;
        white-space: nowrap;
      }
      .pi-tab-pill:hover {
        background: var(--surf);
        border-color: var(--tl-teal);
        color: var(--ink-strong);
      }
      .pi-tab-pill.is-active {
        background: var(--tl-teal);
        border-color: var(--tl-teal);
        color: #fff;
        box-shadow: 0 1px 3px rgba(13, 148, 136, 0.25);
      }
      .pi-pill-badge {
        font-size: 0.65rem;
        font-weight: 700;
        letter-spacing: 0.4px;
        padding: 1px 5px;
        border-radius: 4px;
        background: rgba(0, 0, 0, 0.05);
        color: var(--mute);
      }
      .pi-tab-pill.is-active .pi-pill-badge {
        background: rgba(255, 255, 255, 0.25);
        color: #fff;
      }

      /* Compact Clinical Metadata Strip */
      .pi-meta-strip {
        display: flex;
        align-items: center;
        justify-content: space-between;
        flex-wrap: wrap;
        gap: 10px;
        padding: 8px 14px;
        background: var(--surf2);
        border: 1px solid var(--line);
        border-radius: 6px;
        margin-bottom: 12px;
      }
      .pi-strip-title {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .pi-strip-badge {
        font-size: 0.68rem;
        font-weight: 700;
        letter-spacing: 0.5px;
        padding: 2px 7px;
        border-radius: 4px;
        background: var(--pri-soft, rgba(13, 148, 136, 0.1));
        color: var(--tl-teal);
      }
      .pi-strip-heading {
        font-size: 0.92rem;
        color: var(--ink-strong);
      }
      .pi-strip-tags {
        display: flex;
        align-items: center;
        gap: 12px;
        flex-wrap: wrap;
        font-size: 0.8rem;
      }
      .pi-tag {
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }
      .pi-tag-label {
        color: var(--mute);
        font-size: 0.76rem;
      }
      .pi-tag-val {
        color: var(--ink);
      }
      .pi-tag-val--phone {
        font-weight: 600;
        color: var(--tl-teal);
        direction: ltr;
        display: inline-block;
      }
      .pi-tag-sep {
        width: 1px;
        height: 12px;
        background: var(--line);
      }
      .pi-tag-edit {
        background: none;
        border: none;
        color: var(--mute);
        cursor: pointer;
        padding: 2px 4px;
        display: inline-flex;
        align-items: center;
        border-radius: 4px;
        transition: color 0.15s ease;
      }
      .pi-tag-edit:hover {
        color: var(--tl-teal);
      }

      /* 2-Column Content Grid: Fits without excessive scrolling */
      .pi-grid-container {
        display: grid;
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
        gap: 12px;
        align-items: start;
      }
      @media (max-width: 900px) {
        .pi-grid-container {
          grid-template-columns: 1fr;
        }
      }
      .pi-grid-col {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }

      /* Clinical Cards / Boxes - Quiet, Calm Palette */
      .pi-box {
        background: var(--surf);
        border: 1px solid var(--line);
        border-radius: 8px;
        padding: 12px 14px;
      }
      .pi-box-head {
        display: flex;
        align-items: center;
        gap: 7px;
        margin-bottom: 8px;
      }
      .pi-box-title {
        margin: 0;
        font-size: 0.85rem;
        font-weight: 700;
        color: var(--ink-strong);
      }
      .pi-box-list {
        margin: 0;
        padding-inline-start: 18px;
        font-size: 0.82rem;
        line-height: 1.55;
        color: var(--ink);
      }
      .pi-box-list li {
        margin-bottom: 5px;
      }
      .pi-box-list li:last-child {
        margin-bottom: 0;
      }

      /* Calm DOs (Soft subtle green) */
      .pi-box--dos {
        background: rgba(16, 185, 129, 0.03);
        border-color: rgba(16, 185, 129, 0.22);
      }
      .pi-title--ok {
        color: #059669;
      }
      .pi-icon-ok {
        color: #059669;
      }

      /* Calm DONTs (Soft subtle red) */
      .pi-box--donts {
        background: rgba(239, 68, 68, 0.03);
        border-color: rgba(239, 68, 68, 0.22);
      }
      .pi-title--bad {
        color: #dc2626;
      }
      .pi-icon-bad {
        color: #dc2626;
      }

      /* Calm Medications */
      .pi-box--meds {
        background: var(--surf2);
        border-color: var(--line);
        border-inline-start: 3px solid var(--tl-teal);
      }
      .pi-med-text {
        margin: 0;
        font-size: 0.82rem;
        line-height: 1.55;
        color: var(--ink);
      }

      /* Calm Warnings (Soft subtle amber) */
      .pi-box--warnings {
        background: rgba(245, 158, 11, 0.03);
        border-color: rgba(245, 158, 11, 0.22);
      }
      .pi-title--warn {
        color: #b45309;
      }
      .pi-icon-warn {
        color: #b45309;
      }

      .pi-icon-teal {
        color: var(--tl-teal);
      }

      /* Drawer Form Elements */
      .tl-form-stack {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .tl-form-group {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .tl-label {
        font-size: 0.8rem;
        font-weight: 600;
        color: var(--ink);
      }
      .tl-btn-close {
        background: none;
        border: none;
        color: var(--mute);
        cursor: pointer;
        padding: 4px;
        display: inline-flex;
        align-items: center;
        border-radius: 4px;
      }
      .tl-btn-close:hover {
        color: var(--ink);
      }
      .tl-btn.is-copied {
        background: var(--ok-soft, rgba(16, 185, 129, 0.1));
        border-color: #059669;
        color: #059669;
      }

      /* Print Sheet: Hidden on Screen, Shown Only on Print */
      .pi-print-sheet {
        display: none;
      }

      @media print {
        .no-print {
          display: none !important;
        }
        .tl-page {
          padding: 0 !important;
          background: #fff !important;
        }
        .pi-print-sheet {
          display: block !important;
          color: #111 !important;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          padding: 20px 24px;
        }
        .pi-print-head {
          display: flex;
          justify-content: space-between;
          align-items: flex-start;
          border-bottom: 2px solid #333;
          padding-bottom: 12px;
          margin-bottom: 14px;
        }
        .pi-print-clinic {
          margin: 0;
          font-size: 1.3rem;
          font-weight: 800;
          color: #0d9488;
        }
        .pi-print-clinic-sub {
          font-size: 0.8rem;
          color: #555;
        }
        .pi-print-meta {
          text-align: end;
        }
        .pi-print-badge {
          display: inline-block;
          font-size: 0.72rem;
          font-weight: 700;
          background: #0d9488;
          color: #fff;
          padding: 3px 8px;
          border-radius: 4px;
          margin-bottom: 4px;
        }
        .pi-print-date {
          display: block;
          font-size: 0.78rem;
          color: #555;
        }
        .pi-print-patient-bar {
          display: grid;
          grid-template-columns: repeat(3, 1fr);
          gap: 10px;
          background: #f8fafc;
          border: 1px solid #cbd5e1;
          padding: 8px 12px;
          border-radius: 6px;
          font-size: 0.82rem;
          margin-bottom: 14px;
        }
        .pi-print-lbl {
          color: #64748b;
          margin-inline-end: 4px;
        }
        .pi-print-proc-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 12px;
          padding-bottom: 6px;
          border-bottom: 1px solid #e2e8f0;
        }
        .pi-print-proc-header h2 {
          margin: 0;
          font-size: 1.1rem;
          color: #0f172a;
        }
        .pi-print-proc-badge {
          font-size: 0.72rem;
          font-weight: 700;
          color: #0d9488;
          border: 1px solid #0d9488;
          padding: 2px 6px;
          border-radius: 4px;
        }
        .pi-print-section {
          margin-bottom: 12px;
        }
        .pi-print-section h3 {
          margin: 0 0 6px;
          font-size: 0.88rem;
          font-weight: 700;
          color: #0f172a;
        }
        .pi-print-section ul {
          margin: 0;
          padding-inline-start: 20px;
          font-size: 0.8rem;
          line-height: 1.5;
        }
        .pi-print-section li {
          margin-bottom: 4px;
        }
        .pi-print-section p {
          margin: 0;
          font-size: 0.8rem;
          line-height: 1.5;
          background: #f8fafc;
          padding: 8px 12px;
          border-inline-start: 3px solid #0d9488;
        }
        .pi-print-grid {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 14px;
          margin-bottom: 12px;
        }
        .pi-print-col {
          border: 1px solid #cbd5e1;
          border-radius: 6px;
          padding: 8px 12px;
        }
        .pi-print-col h4 {
          margin: 0 0 6px;
          font-size: 0.82rem;
          font-weight: 700;
        }
        .pi-print-col ul {
          margin: 0;
          padding-inline-start: 16px;
          font-size: 0.78rem;
          line-height: 1.45;
        }
        .pi-print-warn-section {
          background: #fffbeb;
          border: 1px solid #fde68a;
          padding: 8px 12px;
          border-radius: 6px;
        }
        .pi-print-warn-section h3 {
          color: #92400e;
        }
        .pi-print-warn-section ul {
          color: #92400e;
        }
        .pi-print-footer {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 36px;
          margin-top: 28px;
          padding-top: 18px;
          border-top: 1px dashed #cbd5e1;
        }
        .pi-print-sig {
          text-align: center;
          font-size: 0.78rem;
          color: #64748b;
        }
        .pi-print-sig-line {
          border-bottom: 1px solid #94a3b8;
          margin-bottom: 6px;
          height: 24px;
        }
      }
    `,
  ],
})
export class ClinicPostInstructionsComponent {
  private readonly route = inject(ActivatedRoute);

  protected readonly categories = INSTRUCTION_CATEGORIES;
  protected readonly selectedCategoryId = signal<string>('extraction');
  protected readonly language = signal<'ar' | 'en'>('ar');
  protected readonly isDrawerOpen = signal<boolean>(false);
  protected readonly copied = signal<boolean>(false);

  protected readonly patientName = signal<string>('Ali Abbas');
  protected readonly doctorName = signal<string>('Dr. Eman Khan');
  protected readonly emergencyPhone = signal<string>('+962 6 500 1234');
  protected readonly instructionDate = signal<string>(new Date().toISOString().slice(0, 10));

  protected readonly selectedCategory = computed(() => {
    const id = this.selectedCategoryId();
    return this.categories.find((c) => c.id === id) ?? this.categories[0];
  });

  constructor() {
    this.route.queryParamMap.subscribe((qp) => {
      const pName = qp.get('patientName');
      const dName = qp.get('doctorName');
      const svc = qp.get('service');
      if (pName) this.patientName.set(pName);
      if (dName) this.doctorName.set(dName);
      if (svc) {
        const lower = svc.toLowerCase();
        if (lower.includes('root') || lower.includes('عصب') || lower.includes('جذور')) {
          this.selectedCategoryId.set('root-canal');
        } else if (lower.includes('implant') || lower.includes('زراع')) {
          this.selectedCategoryId.set('implants');
        } else if (lower.includes('whiten') || lower.includes('تبييض')) {
          this.selectedCategoryId.set('whitening');
        } else if (lower.includes('filling') || lower.includes('حشو') || lower.includes('composite')) {
          this.selectedCategoryId.set('restorations');
        } else {
          this.selectedCategoryId.set('extraction');
        }
      }
    });
  }

  protected toggleLanguage(): void {
    this.language.update((lang) => (lang === 'ar' ? 'en' : 'ar'));
  }

  protected triggerPrint(): void {
    window.print();
  }

  protected async copyToClipboard(): Promise<void> {
    const cat = this.selectedCategory();
    const isAr = this.language() === 'ar';
    const title = isAr ? cat.titleAr : cat.titleEn;
    const imm = isAr ? cat.immediateCare.ar : cat.immediateCare.en;
    const dos = isAr ? cat.dos.ar : cat.dos.en;
    const donts = isAr ? cat.donts.ar : cat.donts.en;
    const med = isAr ? cat.medications.ar : cat.medications.en;
    const warns = isAr ? cat.warningSigns.ar : cat.warningSigns.en;

    const lines = [
      `ToothLogic Care - ${title}`,
      `${isAr ? 'المريض' : 'Patient'}: ${this.patientName()}`,
      `${isAr ? 'الطبيب' : 'Dentist'}: ${this.doctorName()}`,
      `${isAr ? 'التاريخ' : 'Date'}: ${this.instructionDate()}`,
      `----------------------------------------`,
      `[${isAr ? 'تعليمات أول 24 ساعة' : 'Immediate Care'}]`,
      ...imm.map((item, idx) => `${idx + 1}. ${item}`),
      ``,
      `[${isAr ? 'إرشادات العناية الموصى بها' : 'Recommended Care Guidelines'}]`,
      ...dos.map((item) => `+ ${item}`),
      ``,
      `[${isAr ? 'تعليمات ضرورية' : 'Essential Precautions'}]`,
      ...donts.map((item) => `- ${item}`),
      ``,
      `[${isAr ? 'الأدوية والمسكنات' : 'Medications & Pain Control'}]`,
      med,
      ``,
      `[${isAr ? 'علامات تستوجب مراجعة العيادة' : 'Contact Clinic Immediately'}]`,
      ...warns.map((item) => `! ${item}`),
      ``,
      `${isAr ? 'طوارئ العيادة' : 'Clinic Helpline'}: ${this.emergencyPhone()}`,
    ];

    try {
      await navigator.clipboard.writeText(lines.join('\n'));
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2500);
    } catch {
      // Fallback
    }
  }
}
